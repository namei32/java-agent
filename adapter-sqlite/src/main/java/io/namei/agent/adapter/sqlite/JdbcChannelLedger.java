package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import io.namei.agent.kernel.channel.reliability.TurnClaimState;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcChannelLedger implements ChannelLedgerPort {
  private final ChannelLedgerSchemaInitializer schema;
  private final ChannelLedgerFaultInjector faults;
  private final JdbcChannelDeliveryLedger deliveries;

  public JdbcChannelLedger(ChannelLedgerSchemaInitializer schema) {
    this(schema, ignored -> {});
  }

  JdbcChannelLedger(ChannelLedgerSchemaInitializer schema, ChannelLedgerFaultInjector faults) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.faults = Objects.requireNonNull(faults, "faults");
    this.deliveries = new JdbcChannelDeliveryLedger(schema, faults);
  }

  @Override
  public ChannelLedgerResult.Event recordEvent(ChannelLedgerCommand.RecordEvent command) {
    Objects.requireNonNull(command, "command");
    if (command.kind() == InboxEventKind.FEEDBACK) {
      return deliveries.recordFeedback(command);
    }
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> recordEventInTransaction(connection, command));
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public ChannelLedgerResult.TurnStart startTurn(ChannelLedgerCommand.StartTurn command) {
    Objects.requireNonNull(command, "command");
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> startTurnInTransaction(connection, command));
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public ChannelLedgerResult.Terminal recordTerminal(ChannelLedgerCommand.RecordTerminal command) {
    return deliveries.recordTerminal(command);
  }

  @Override
  public Optional<ChannelLedgerResult.DeliveryWork> claimNextDelivery(
      ChannelLedgerCommand.ClaimDelivery command) {
    return deliveries.claimNext(command);
  }

  @Override
  public ChannelLedgerResult.DeliveryUpdate recordDeliveryOutcome(
      ChannelLedgerCommand.RecordDeliveryOutcome command) {
    return deliveries.recordOutcome(command);
  }

  @Override
  public ChannelLedgerResult.Recovery recover(ChannelLedgerCommand.Recover command) {
    Objects.requireNonNull(command, "command");
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> recoverInTransaction(connection, command));
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public ChannelLedgerResult.Cleanup cleanup(ChannelLedgerCommand.Cleanup command) {
    Objects.requireNonNull(command, "command");
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> cleanupInTransaction(connection, command));
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  @Override
  public ChannelLedgerResult.Snapshot snapshot(ChannelInstanceId instance) {
    Objects.requireNonNull(instance, "instance");
    try (var connection = schema.openConnection()) {
      CursorState cursor = findCursor(connection, instance).orElse(CursorState.empty());
      return new ChannelLedgerResult.Snapshot(
          cursor.nextSequence(),
          deliveries.countPending(connection, instance),
          countUnknownExecutions(connection, instance),
          deliveries.countUnknown(connection, instance),
          "");
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  private ChannelLedgerResult.TurnStart startTurnInTransaction(
      Connection connection, ChannelLedgerCommand.StartTurn command) throws SQLException {
    CursorState cursor = findCursor(connection, command.instance()).orElse(CursorState.empty());
    Optional<StoredClaim> found =
        findClaimByMessage(connection, command.instance(), command.externalMessageId());
    if (found.isEmpty()) {
      return staleTurnStart(0, cursor);
    }
    StoredClaim claim = found.orElseThrow();
    Optional<StoredEvent> foundEvent =
        findEventById(connection, command.instance(), command.externalEventId());
    if (claim.revision() != command.expectedRevision()
        || !claim.turnId().equals(command.turnId())
        || foundEvent.isEmpty()
        || !claim.turnId().equals(foundEvent.orElseThrow().turnId())
        || (claim.state() != TurnClaimState.RESERVED
            && claim.state() != TurnClaimState.START_RETRYABLE)) {
      return staleTurnStart(claim.revision(), cursor);
    }
    StoredEvent event = foundEvent.orElseThrow();
    if (event.externalSequence() < cursor.nextSequence()) {
      return staleTurnStart(claim.revision(), cursor);
    }

    if (claim.startAttempts() >= 3) {
      StoredClaim exhausted =
          markExecutionUnknown(connection, claim, command.startedAt().toString());
      faults.hit(ChannelLedgerFaultPoint.TURN_STARTED_BEFORE_COMMIT);
      return staleTurnStart(exhausted.revision(), cursor);
    }

    StoredClaim reserved = claim;
    if (claim.state() == TurnClaimState.START_RETRYABLE) {
      reserved = transitionRetryableToReserved(connection, claim, command.startedAt().toString());
    }
    CursorState advanced =
        advanceCursor(
            connection,
            command.instance(),
            event.externalSequence(),
            command.startedAt().toString(),
            cursor);
    StoredClaim running = transitionReservedToRunning(connection, reserved, command);
    faults.hit(ChannelLedgerFaultPoint.TURN_STARTED_BEFORE_COMMIT);
    return new ChannelLedgerResult.TurnStart(
        ChannelLedgerResult.TurnStartStatus.STARTED, running.revision(), advanced.nextSequence());
  }

  private ChannelLedgerResult.Recovery recoverInTransaction(
      Connection connection, ChannelLedgerCommand.Recover command) throws SQLException {
    List<RecoveryCandidate> candidates =
        findRecoveryCandidates(connection, command, command.batchSize());
    boolean remaining = candidates.size() > command.batchSize();
    int processed = Math.min(candidates.size(), command.batchSize());
    for (int index = 0; index < processed; index++) {
      recoverClaim(connection, candidates.get(index), command.recoveredAt().toString());
    }
    if (!remaining) {
      JdbcChannelDeliveryLedger.RecoverySlice deliveryRecovery =
          deliveries.recover(connection, command, command.batchSize() - processed);
      processed += deliveryRecovery.processed();
      remaining = deliveryRecovery.remaining();
    }
    faults.hit(ChannelLedgerFaultPoint.RECOVERY_BEFORE_COMMIT);
    return new ChannelLedgerResult.Recovery(processed, remaining);
  }

  private ChannelLedgerResult.Cleanup cleanupInTransaction(
      Connection connection, ChannelLedgerCommand.Cleanup command) throws SQLException {
    int processed = deliveries.cleanupResolved(connection, command, command.batchSize());
    int remainingBudget = command.batchSize() - processed;
    if (remainingBudget > 0) {
      processed += cleanupEvents(connection, command, remainingBudget);
    }
    boolean capacityAvailable = capacityAvailable(connection, command);
    faults.hit(ChannelLedgerFaultPoint.CLEANUP_BEFORE_COMMIT);
    return new ChannelLedgerResult.Cleanup(processed, capacityAvailable);
  }

  private static Optional<StoredEvent> findEventById(
      Connection connection, ChannelInstanceId instance, String externalEventId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT external_event_id, external_sequence, event_fingerprint, decision, turn_id
            FROM channel_inbox_events
            WHERE channel = ? AND instance_id = ? AND external_event_id = ?
            """)) {
      statement.setString(1, instance.channel());
      statement.setString(2, instance.value());
      statement.setString(3, externalEventId);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        StoredEvent event = readEvent(rows);
        if (rows.next()) {
          throw new SQLException("duplicate channel event id");
        }
        return Optional.of(event);
      }
    }
  }

  private static StoredClaim transitionRetryableToReserved(
      Connection connection, StoredClaim claim, String updatedAt) throws SQLException {
    long revision = increment(claim.revision());
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_turn_claims
            SET state = 'RESERVED', revision = ?, updated_at = ?
            WHERE channel = ? AND instance_id = ? AND external_message_id = ?
              AND state = 'START_RETRYABLE' AND revision = ?
            """)) {
      bindClaimTransition(statement, revision, updatedAt, claim);
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    return claim.withState(TurnClaimState.RESERVED, revision);
  }

  private static StoredClaim transitionReservedToRunning(
      Connection connection, StoredClaim claim, ChannelLedgerCommand.StartTurn command)
      throws SQLException {
    long revision = increment(claim.revision());
    int attempts = Math.addExact(claim.startAttempts(), 1);
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_turn_claims
            SET state = 'RUNNING', start_attempts = ?, owner_id = ?, lease_expires_at = ?,
              revision = ?, updated_at = ?
            WHERE channel = ? AND instance_id = ? AND external_message_id = ?
              AND state = 'RESERVED' AND revision = ?
            """)) {
      statement.setInt(1, attempts);
      statement.setString(2, command.ownerId());
      statement.setString(3, command.leaseExpiresAt().toString());
      statement.setLong(4, revision);
      statement.setString(5, command.startedAt().toString());
      statement.setString(6, claim.instance().channel());
      statement.setString(7, claim.instance().value());
      statement.setString(8, claim.externalMessageId());
      statement.setLong(9, claim.revision());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    return new StoredClaim(
        claim.instance(),
        claim.externalMessageId(),
        claim.requestFingerprint(),
        claim.turnId(),
        TurnClaimState.RUNNING,
        attempts,
        command.ownerId(),
        command.leaseExpiresAt().toString(),
        revision);
  }

  private static StoredClaim markExecutionUnknown(
      Connection connection, StoredClaim claim, String updatedAt) throws SQLException {
    StoredClaim retryable = claim;
    if (claim.state() == TurnClaimState.RESERVED) {
      long revision = increment(claim.revision());
      try (var statement =
          connection.prepareStatement(
              """
              UPDATE channel_turn_claims
              SET state = 'START_RETRYABLE', revision = ?, updated_at = ?
              WHERE channel = ? AND instance_id = ? AND external_message_id = ?
                AND state = 'RESERVED' AND revision = ?
              """)) {
        bindClaimTransition(statement, revision, updatedAt, claim);
        if (statement.executeUpdate() != 1) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
      }
      retryable = claim.withState(TurnClaimState.START_RETRYABLE, revision);
    }
    long revision = increment(retryable.revision());
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_turn_claims
            SET state = 'EXECUTION_UNKNOWN', revision = ?, updated_at = ?
            WHERE channel = ? AND instance_id = ? AND external_message_id = ?
              AND state = 'START_RETRYABLE' AND revision = ?
            """)) {
      bindClaimTransition(statement, revision, updatedAt, retryable);
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    return retryable.withState(TurnClaimState.EXECUTION_UNKNOWN, revision);
  }

  private static void bindClaimTransition(
      java.sql.PreparedStatement statement, long revision, String updatedAt, StoredClaim claim)
      throws SQLException {
    statement.setLong(1, revision);
    statement.setString(2, updatedAt);
    statement.setString(3, claim.instance().channel());
    statement.setString(4, claim.instance().value());
    statement.setString(5, claim.externalMessageId());
    statement.setLong(6, claim.revision());
  }

  private static ChannelLedgerResult.TurnStart staleTurnStart(long revision, CursorState cursor) {
    return new ChannelLedgerResult.TurnStart(
        ChannelLedgerResult.TurnStartStatus.STALE, revision, cursor.nextSequence());
  }

  private ChannelLedgerResult.Event recordEventInTransaction(
      Connection connection, ChannelLedgerCommand.RecordEvent command) throws SQLException {
    return switch (command.kind()) {
      case IGNORED, CONTROL -> recordSimpleEventInTransaction(connection, command);
      case TURN -> recordTurnEventInTransaction(connection, command);
      case FEEDBACK -> throw ChannelLedgerRepositoryException.operationFailed(null);
    };
  }

  private ChannelLedgerResult.Event recordSimpleEventInTransaction(
      Connection connection, ChannelLedgerCommand.RecordEvent command) throws SQLException {
    Optional<StoredEvent> existing = findEvent(connection, command);
    CursorState cursor = findCursor(connection, command.instance()).orElse(CursorState.empty());
    if (existing.isPresent()) {
      requireMatchingReplay(existing.orElseThrow(), command, cursor);
      return eventResult(cursor);
    }
    if (command.externalSequence() < cursor.nextSequence()) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }

    insertEvent(connection, command, decision(command.kind()), null);
    CursorState advanced = advanceCursor(connection, command, cursor);
    faults.hit(ChannelLedgerFaultPoint.EVENT_RECORDED_BEFORE_COMMIT);
    return eventResult(advanced);
  }

  private ChannelLedgerResult.Event recordTurnEventInTransaction(
      Connection connection, ChannelLedgerCommand.RecordEvent command) throws SQLException {
    ChannelLedgerCommand.TurnReservation reservation = command.reservation();
    CursorState cursor = findCursor(connection, command.instance()).orElse(CursorState.empty());
    Optional<StoredEvent> storedEvent = findEvent(connection, command);
    if (storedEvent.isPresent()) {
      StoredEvent event = storedEvent.orElseThrow();
      requireMatchingTurnEvent(event, command);
      StoredClaim claim =
          findClaimByMessage(connection, command.instance(), reservation.externalMessageId())
              .orElseThrow(ChannelLedgerRepositoryException::idempotencyConflict);
      requireMatchingClaim(claim, reservation, event.turnId());
      ClaimReplay replay = replayClaim(connection, claim, command.recordedAt().toString());
      cursor = advanceDuplicateCursorIfSafe(connection, command, replay.claim(), cursor);
      faults.hit(ChannelLedgerFaultPoint.EVENT_RECORDED_BEFORE_COMMIT);
      return claimResult(replay.status(), replay.claim(), cursor);
    }
    if (command.externalSequence() < cursor.nextSequence()) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }

    Optional<StoredClaim> existing =
        findClaimByMessage(connection, command.instance(), reservation.externalMessageId());
    if (existing.isEmpty()) {
      if (findClaimByTurnId(connection, reservation.turnId()).isPresent()) {
        throw ChannelLedgerRepositoryException.idempotencyConflict();
      }
      StoredClaim created = insertClaim(connection, command);
      insertEvent(connection, command, "TURN_RESERVED", created.turnId());
      faults.hit(ChannelLedgerFaultPoint.EVENT_RECORDED_BEFORE_COMMIT);
      return claimResult(ChannelLedgerResult.InboxStatus.RESERVED_NEW, created, cursor);
    }

    StoredClaim claim = existing.orElseThrow();
    requireMatchingClaim(claim, reservation, claim.turnId());
    insertEvent(connection, command, "DUPLICATE", claim.turnId());
    ClaimReplay replay = replayClaim(connection, claim, command.recordedAt().toString());
    cursor = advanceDuplicateCursorIfSafe(connection, command, replay.claim(), cursor);
    faults.hit(ChannelLedgerFaultPoint.EVENT_RECORDED_BEFORE_COMMIT);
    return claimResult(replay.status(), replay.claim(), cursor);
  }

  private static void requireMatchingTurnEvent(
      StoredEvent stored, ChannelLedgerCommand.RecordEvent command) {
    if (!stored.externalEventId().equals(command.externalEventId())
        || stored.externalSequence() != command.externalSequence()
        || !stored.eventFingerprint().equals(command.eventFingerprint())
        || !(stored.decision().equals("TURN_RESERVED") || stored.decision().equals("DUPLICATE"))
        || stored.turnId() == null) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
  }

  private static void requireMatchingClaim(
      StoredClaim claim, ChannelLedgerCommand.TurnReservation reservation, String expectedTurnId) {
    if (!claim.externalMessageId().equals(reservation.externalMessageId())
        || !claim.requestFingerprint().equals(reservation.requestFingerprint())
        || !claim.turnId().equals(expectedTurnId)) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
  }

  private static ClaimReplay replayClaim(Connection connection, StoredClaim claim, String updatedAt)
      throws SQLException {
    return switch (claim.state()) {
      case RESERVED -> new ClaimReplay(ChannelLedgerResult.InboxStatus.START_RETRYABLE, claim);
      case START_RETRYABLE -> {
        if (claim.startAttempts() >= 3) {
          StoredClaim unknown = markExecutionUnknown(connection, claim, updatedAt);
          yield new ClaimReplay(ChannelLedgerResult.InboxStatus.EXECUTION_UNKNOWN, unknown);
        }
        StoredClaim reserved = transitionRetryableToReserved(connection, claim, updatedAt);
        yield new ClaimReplay(ChannelLedgerResult.InboxStatus.START_RETRYABLE, reserved);
      }
      case RUNNING, TERMINAL_RECORDED, EXECUTION_UNKNOWN ->
          new ClaimReplay(status(claim.state()), claim);
    };
  }

  private static CursorState advanceDuplicateCursorIfSafe(
      Connection connection,
      ChannelLedgerCommand.RecordEvent command,
      StoredClaim claim,
      CursorState cursor)
      throws SQLException {
    if (claim.state() != TurnClaimState.RUNNING
        && claim.state() != TurnClaimState.TERMINAL_RECORDED
        && claim.state() != TurnClaimState.EXECUTION_UNKNOWN) {
      return cursor;
    }
    if (!cursor.persisted()) {
      if (claim.state() == TurnClaimState.EXECUTION_UNKNOWN && claim.startAttempts() >= 3) {
        return cursor;
      }
      throw ChannelLedgerRepositoryException.operationFailed(null);
    }
    if (command.externalSequence() < cursor.nextSequence()) {
      return cursor;
    }
    return advanceCursor(
        connection,
        command.instance(),
        command.externalSequence(),
        command.recordedAt().toString(),
        cursor);
  }

  private static ChannelLedgerResult.InboxStatus status(TurnClaimState state) {
    return switch (state) {
      case RESERVED -> ChannelLedgerResult.InboxStatus.RESERVED_NEW;
      case START_RETRYABLE -> ChannelLedgerResult.InboxStatus.START_RETRYABLE;
      case RUNNING -> ChannelLedgerResult.InboxStatus.IN_PROGRESS;
      case TERMINAL_RECORDED -> ChannelLedgerResult.InboxStatus.ALREADY_TERMINAL;
      case EXECUTION_UNKNOWN -> ChannelLedgerResult.InboxStatus.EXECUTION_UNKNOWN;
    };
  }

  private static ChannelLedgerResult.Event claimResult(
      ChannelLedgerResult.InboxStatus status, StoredClaim claim, CursorState cursor) {
    return new ChannelLedgerResult.Event(
        status, claim.turnId(), claim.revision(), null, cursor.nextSequence());
  }

  private static Optional<StoredEvent> findEvent(
      Connection connection, ChannelLedgerCommand.RecordEvent command) throws SQLException {
    var matches = new ArrayList<StoredEvent>();
    try (var statement =
        connection.prepareStatement(
            """
            SELECT external_event_id, external_sequence, event_fingerprint, decision, turn_id
            FROM channel_inbox_events
            WHERE channel = ? AND instance_id = ?
              AND (external_event_id = ? OR external_sequence = ?)
            ORDER BY external_event_id
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.externalEventId());
      statement.setLong(4, command.externalSequence());
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          matches.add(readEvent(rows));
        }
      }
    }
    if (matches.size() > 1) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    return matches.stream().findFirst();
  }

  private static StoredEvent readEvent(ResultSet rows) throws SQLException {
    long sequence = exactNonNegativeLong(rows, "external_sequence");
    return new StoredEvent(
        rows.getString("external_event_id"),
        sequence,
        rows.getString("event_fingerprint"),
        rows.getString("decision"),
        rows.getString("turn_id"));
  }

  private static Optional<StoredClaim> findClaimByMessage(
      Connection connection, ChannelInstanceId instance, String externalMessageId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT external_message_id, request_fingerprint, turn_id, state,
              start_attempts, owner_id, lease_expires_at, revision
            FROM channel_turn_claims
            WHERE channel = ? AND instance_id = ? AND external_message_id = ?
            """)) {
      statement.setString(1, instance.channel());
      statement.setString(2, instance.value());
      statement.setString(3, externalMessageId);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        StoredClaim claim = readClaim(rows, instance);
        if (rows.next()) {
          throw new SQLException("duplicate channel turn claim");
        }
        return Optional.of(claim);
      }
    }
  }

  private static Optional<StoredClaim> findClaimByTurnId(Connection connection, String turnId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT channel, instance_id, external_message_id, request_fingerprint, turn_id, state,
              start_attempts, owner_id, lease_expires_at, revision
            FROM channel_turn_claims
            WHERE turn_id = ?
            """)) {
      statement.setString(1, turnId);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        var instance =
            new ChannelInstanceId(rows.getString("channel"), rows.getString("instance_id"));
        StoredClaim claim = readClaim(rows, instance);
        if (rows.next()) {
          throw new SQLException("duplicate channel turn id");
        }
        return Optional.of(claim);
      }
    }
  }

  private static StoredClaim readClaim(ResultSet rows, ChannelInstanceId instance)
      throws SQLException {
    try {
      return new StoredClaim(
          instance,
          rows.getString("external_message_id"),
          rows.getString("request_fingerprint"),
          rows.getString("turn_id"),
          TurnClaimState.valueOf(rows.getString("state")),
          exactNonNegativeInt(rows, "start_attempts"),
          rows.getString("owner_id"),
          rows.getString("lease_expires_at"),
          exactNonNegativeLong(rows, "revision"));
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new SQLException("invalid channel claim state", exception);
    }
  }

  private static StoredClaim insertClaim(
      Connection connection, ChannelLedgerCommand.RecordEvent command) throws SQLException {
    ChannelLedgerCommand.TurnReservation reservation = command.reservation();
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO channel_turn_claims (
              channel, instance_id, external_message_id, request_fingerprint, turn_id,
              state, start_attempts, owner_id, lease_expires_at, revision, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'RESERVED', 0, NULL, NULL, 0, ?, ?)
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, reservation.externalMessageId());
      statement.setString(4, reservation.requestFingerprint());
      statement.setString(5, reservation.turnId());
      statement.setString(6, command.recordedAt().toString());
      statement.setString(7, command.recordedAt().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("channel claim insert count is not one");
      }
    }
    return new StoredClaim(
        command.instance(),
        reservation.externalMessageId(),
        reservation.requestFingerprint(),
        reservation.turnId(),
        TurnClaimState.RESERVED,
        0,
        null,
        null,
        0);
  }

  private static List<RecoveryCandidate> findRecoveryCandidates(
      Connection connection, ChannelLedgerCommand.Recover command, int limit) throws SQLException {
    var candidates = new ArrayList<RecoveryCandidate>();
    try (var statement =
        connection.prepareStatement(
            """
            SELECT external_message_id, state, start_attempts, revision
            FROM channel_turn_claims
            WHERE channel = ? AND instance_id = ?
              AND (state = 'RESERVED' OR (state = 'RUNNING' AND owner_id <> ?))
            ORDER BY updated_at ASC, turn_id ASC
            LIMIT ?
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.newOwnerId());
      statement.setInt(4, Math.addExact(limit, 1));
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          try {
            candidates.add(
                new RecoveryCandidate(
                    command.instance(),
                    rows.getString("external_message_id"),
                    TurnClaimState.valueOf(rows.getString("state")),
                    exactNonNegativeInt(rows, "start_attempts"),
                    exactNonNegativeLong(rows, "revision")));
          } catch (IllegalArgumentException | NullPointerException exception) {
            throw new SQLException("invalid recovery claim", exception);
          }
        }
      }
    }
    return List.copyOf(candidates);
  }

  private static void recoverClaim(
      Connection connection, RecoveryCandidate candidate, String updatedAt) throws SQLException {
    TurnClaimState target =
        candidate.state() == TurnClaimState.RESERVED
            ? TurnClaimState.START_RETRYABLE
            : TurnClaimState.EXECUTION_UNKNOWN;
    int attempts =
        candidate.state() == TurnClaimState.RESERVED
            ? Math.min(3, Math.addExact(candidate.startAttempts(), 1))
            : candidate.startAttempts();
    long revision = increment(candidate.revision());
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_turn_claims
            SET state = ?, start_attempts = ?, owner_id = NULL, lease_expires_at = NULL,
              revision = ?, updated_at = ?
            WHERE channel = ? AND instance_id = ? AND external_message_id = ?
              AND state = ? AND revision = ?
            """)) {
      statement.setString(1, target.name());
      statement.setInt(2, attempts);
      statement.setLong(3, revision);
      statement.setString(4, updatedAt);
      statement.setString(5, candidate.instance().channel());
      statement.setString(6, candidate.instance().value());
      statement.setString(7, candidate.externalMessageId());
      statement.setString(8, candidate.state().name());
      statement.setLong(9, candidate.revision());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
  }

  private static long countUnknownExecutions(Connection connection, ChannelInstanceId instance)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM channel_turn_claims
            WHERE channel = ? AND instance_id = ? AND state = 'EXECUTION_UNKNOWN'
            """)) {
      statement.setString(1, instance.channel());
      statement.setString(2, instance.value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("channel unknown execution count missing");
        }
        long count = exactNonNegativeLong(rows, 1);
        if (rows.next()) {
          throw new SQLException("duplicate channel unknown execution count");
        }
        return count;
      }
    }
  }

  private static int cleanupEvents(
      Connection connection, ChannelLedgerCommand.Cleanup command, int limit) throws SQLException {
    var events = new ArrayList<EventKey>();
    try (var statement =
        connection.prepareStatement(
            """
            SELECT e.external_event_id
            FROM channel_inbox_events e
            JOIN channel_cursors c
              ON c.channel = e.channel AND c.instance_id = e.instance_id
            WHERE e.channel = ? AND e.instance_id = ?
              AND e.turn_id IS NULL
              AND e.external_sequence < c.next_sequence
              AND e.created_at < ?
            ORDER BY e.external_sequence ASC, e.external_event_id ASC
            LIMIT ?
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.cutoff().toString());
      statement.setInt(4, limit);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          events.add(new EventKey(rows.getString("external_event_id")));
        }
      }
    }
    for (EventKey event : events) {
      try (var statement =
          connection.prepareStatement(
              """
              DELETE FROM channel_inbox_events
              WHERE channel = ? AND instance_id = ? AND external_event_id = ?
                AND turn_id IS NULL AND created_at < ?
              """)) {
        statement.setString(1, command.instance().channel());
        statement.setString(2, command.instance().value());
        statement.setString(3, event.externalEventId());
        statement.setString(4, command.cutoff().toString());
        if (statement.executeUpdate() != 1) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
      }
    }
    return events.size();
  }

  private boolean capacityAvailable(Connection connection, ChannelLedgerCommand.Cleanup command)
      throws SQLException {
    long inboxRecords = countInboxCapacity(connection, command.instance());
    long deliveryRecords = deliveries.countActiveCapacity(connection, command.instance());
    return inboxRecords < command.maxInboxRecords()
        && deliveryRecords < command.maxDeliveryRecords();
  }

  private static long countInboxCapacity(Connection connection, ChannelInstanceId instance)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT
              (SELECT COUNT(*) FROM channel_inbox_events
                WHERE channel = ? AND instance_id = ?)
              +
              (SELECT COUNT(*) FROM channel_turn_claims
                WHERE channel = ? AND instance_id = ?)
            """)) {
      statement.setString(1, instance.channel());
      statement.setString(2, instance.value());
      statement.setString(3, instance.channel());
      statement.setString(4, instance.value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("inbox capacity count missing");
        }
        long count = exactNonNegativeLong(rows, 1);
        if (rows.next()) {
          throw new SQLException("duplicate inbox capacity count");
        }
        return count;
      }
    }
  }

  private static Optional<CursorState> findCursor(Connection connection, ChannelInstanceId instance)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT next_sequence, revision
            FROM channel_cursors
            WHERE channel = ? AND instance_id = ?
            """)) {
      statement.setString(1, instance.channel());
      statement.setString(2, instance.value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        var cursor =
            new CursorState(
                exactNonNegativeLong(rows, "next_sequence"),
                exactNonNegativeLong(rows, "revision"),
                true);
        if (rows.next()) {
          throw new SQLException("duplicate channel cursor");
        }
        return Optional.of(cursor);
      }
    }
  }

  private static void requireMatchingReplay(
      StoredEvent stored, ChannelLedgerCommand.RecordEvent command, CursorState cursor) {
    if (!cursor.persisted()
        || cursor.nextSequence() <= stored.externalSequence()
        || !stored.externalEventId().equals(command.externalEventId())
        || stored.externalSequence() != command.externalSequence()
        || !stored.eventFingerprint().equals(command.eventFingerprint())
        || !stored.decision().equals(decision(command.kind()))
        || stored.turnId() != null) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
  }

  private static void insertEvent(
      Connection connection,
      ChannelLedgerCommand.RecordEvent command,
      String decision,
      String turnId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO channel_inbox_events (
              channel, instance_id, external_event_id, external_sequence,
              event_fingerprint, decision, turn_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.externalEventId());
      statement.setLong(4, command.externalSequence());
      statement.setString(5, command.eventFingerprint());
      statement.setString(6, decision);
      statement.setString(7, turnId);
      statement.setString(8, command.recordedAt().toString());
      statement.setString(9, command.recordedAt().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("channel event insert count is not one");
      }
    }
  }

  private static CursorState advanceCursor(
      Connection connection, ChannelLedgerCommand.RecordEvent command, CursorState cursor)
      throws SQLException {
    return advanceCursor(
        connection,
        command.instance(),
        command.externalSequence(),
        command.recordedAt().toString(),
        cursor);
  }

  private static CursorState advanceCursor(
      Connection connection,
      ChannelInstanceId instance,
      long externalSequence,
      String updatedAt,
      CursorState cursor)
      throws SQLException {
    long nextSequence;
    try {
      nextSequence = Math.addExact(externalSequence, 1L);
    } catch (ArithmeticException exception) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    if (!cursor.persisted()) {
      try (var statement =
          connection.prepareStatement(
              """
              INSERT INTO channel_cursors (
                channel, instance_id, next_sequence, revision, updated_at
              ) VALUES (?, ?, ?, 0, ?)
              """)) {
        statement.setString(1, instance.channel());
        statement.setString(2, instance.value());
        statement.setLong(3, nextSequence);
        statement.setString(4, updatedAt);
        if (statement.executeUpdate() != 1) {
          throw new SQLException("channel cursor insert count is not one");
        }
      }
      return new CursorState(nextSequence, 0, true);
    }

    long revision;
    try {
      revision = Math.addExact(cursor.revision(), 1L);
    } catch (ArithmeticException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_cursors
            SET next_sequence = ?, revision = ?, updated_at = ?
            WHERE channel = ? AND instance_id = ?
              AND next_sequence = ? AND revision = ?
            """)) {
      statement.setLong(1, nextSequence);
      statement.setLong(2, revision);
      statement.setString(3, updatedAt);
      statement.setString(4, instance.channel());
      statement.setString(5, instance.value());
      statement.setLong(6, cursor.nextSequence());
      statement.setLong(7, cursor.revision());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    return new CursorState(nextSequence, revision, true);
  }

  private static String decision(InboxEventKind kind) {
    return switch (kind) {
      case IGNORED -> "IGNORED";
      case CONTROL -> "CONTROL";
      case FEEDBACK, TURN -> throw ChannelLedgerRepositoryException.operationFailed(null);
    };
  }

  private static ChannelLedgerResult.Event eventResult(CursorState cursor) {
    return new ChannelLedgerResult.Event(
        ChannelLedgerResult.InboxStatus.EVENT_RECORDED,
        null,
        cursor.revision(),
        null,
        cursor.nextSequence());
  }

  private static long exactNonNegativeLong(ResultSet rows, String column) throws SQLException {
    long value = rows.getLong(column);
    if (rows.wasNull() || value < 0) {
      throw new SQLException("invalid channel ledger integer");
    }
    return value;
  }

  private static long exactNonNegativeLong(ResultSet rows, int column) throws SQLException {
    long value = rows.getLong(column);
    if (rows.wasNull() || value < 0) {
      throw new SQLException("invalid channel ledger integer");
    }
    return value;
  }

  private static int exactNonNegativeInt(ResultSet rows, String column) throws SQLException {
    long value = exactNonNegativeLong(rows, column);
    if (value > Integer.MAX_VALUE) {
      throw new SQLException("channel ledger integer is out of range");
    }
    return Math.toIntExact(value);
  }

  private static long increment(long value) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  private static <T> T transaction(Connection connection, SqlWork<T> work) throws SQLException {
    executeTransactionControl(connection, "BEGIN IMMEDIATE");
    try {
      T result = work.run();
      executeTransactionControl(connection, "COMMIT");
      return result;
    } catch (SQLException | RuntimeException exception) {
      rollbackPreservingFailure(connection, exception);
      throw exception;
    }
  }

  private static void executeTransactionControl(Connection connection, String command)
      throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(command);
    }
  }

  private static void rollbackPreservingFailure(Connection connection, Exception failure) {
    try {
      executeTransactionControl(connection, "ROLLBACK");
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private record CursorState(long nextSequence, long revision, boolean persisted) {
    private static CursorState empty() {
      return new CursorState(0, 0, false);
    }
  }

  private record StoredEvent(
      String externalEventId,
      long externalSequence,
      String eventFingerprint,
      String decision,
      String turnId) {}

  private record StoredClaim(
      ChannelInstanceId instance,
      String externalMessageId,
      String requestFingerprint,
      String turnId,
      TurnClaimState state,
      int startAttempts,
      String ownerId,
      String leaseExpiresAt,
      long revision) {
    private StoredClaim withState(TurnClaimState newState, long newRevision) {
      return new StoredClaim(
          instance,
          externalMessageId,
          requestFingerprint,
          turnId,
          newState,
          startAttempts,
          newState == TurnClaimState.RUNNING ? ownerId : null,
          newState == TurnClaimState.RUNNING ? leaseExpiresAt : null,
          newRevision);
    }
  }

  private record ClaimReplay(ChannelLedgerResult.InboxStatus status, StoredClaim claim) {}

  private record RecoveryCandidate(
      ChannelInstanceId instance,
      String externalMessageId,
      TurnClaimState state,
      int startAttempts,
      long revision) {}

  private record EventKey(String externalEventId) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }
}
