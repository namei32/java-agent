package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.DeliveryAttemptOutcome;
import io.namei.agent.kernel.channel.reliability.DeliveryEnvelope;
import io.namei.agent.kernel.channel.reliability.DeliveryPartPayload;
import io.namei.agent.kernel.channel.reliability.DeliveryPartState;
import io.namei.agent.kernel.channel.reliability.DeliveryState;
import io.namei.agent.kernel.channel.reliability.TurnClaimState;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class JdbcChannelDeliveryLedger {
  private static final String RETRY_BUDGET_EXCEEDED = "RETRY_BUDGET_EXCEEDED";

  private final ChannelLedgerSchemaInitializer schema;
  private final ChannelLedgerFaultInjector faults;

  JdbcChannelDeliveryLedger(
      ChannelLedgerSchemaInitializer schema, ChannelLedgerFaultInjector faults) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.faults = Objects.requireNonNull(faults, "faults");
  }

  ChannelLedgerResult.Terminal recordTerminal(ChannelLedgerCommand.RecordTerminal command) {
    Objects.requireNonNull(command, "command");
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> recordTerminalInTransaction(connection, command));
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  ChannelLedgerResult.Event recordFeedback(ChannelLedgerCommand.RecordEvent command) {
    Objects.requireNonNull(command, "command");
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> recordFeedbackInTransaction(connection, command));
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  Optional<ChannelLedgerResult.DeliveryWork> claimNext(ChannelLedgerCommand.ClaimDelivery command) {
    Objects.requireNonNull(command, "command");
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> claimNextInTransaction(connection, command));
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  ChannelLedgerResult.DeliveryUpdate recordOutcome(
      ChannelLedgerCommand.RecordDeliveryOutcome command) {
    Objects.requireNonNull(command, "command");
    try (var connection = schema.openConnection()) {
      return transaction(connection, () -> recordOutcomeInTransaction(connection, command));
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  long countPending(Connection connection, ChannelInstanceId instance) throws SQLException {
    return countDeliveries(connection, instance, "state IN ('PENDING', 'DELIVERING')");
  }

  long countUnknown(Connection connection, ChannelInstanceId instance) throws SQLException {
    return countDeliveries(connection, instance, "state = 'UNKNOWN'");
  }

  long countActiveCapacity(Connection connection, ChannelInstanceId instance) throws SQLException {
    return countDeliveries(
        connection, instance, "(payload_pruned = 0 OR state NOT IN ('DELIVERED', 'FAILED'))");
  }

  RecoverySlice recover(Connection connection, ChannelLedgerCommand.Recover command, int limit)
      throws SQLException {
    List<DeliveryRecoveryCandidate> candidates =
        findDeliveryRecoveryCandidates(connection, command, limit);
    int processed = Math.min(candidates.size(), limit);
    for (int index = 0; index < processed; index++) {
      recoverDelivery(connection, candidates.get(index), command.recoveredAt());
    }
    return new RecoverySlice(processed, candidates.size() > limit);
  }

  int cleanupResolved(Connection connection, ChannelLedgerCommand.Cleanup command, int limit)
      throws SQLException {
    var deliveryIds = new ArrayList<String>();
    try (var statement =
        connection.prepareStatement(
            """
            SELECT delivery_id
            FROM channel_deliveries
            WHERE channel = ? AND instance_id = ?
              AND state IN ('DELIVERED', 'FAILED')
              AND payload_pruned = 0 AND updated_at < ?
            ORDER BY updated_at ASC, delivery_id ASC
            LIMIT ?
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.cutoff().toString());
      statement.setInt(4, limit);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          deliveryIds.add(rows.getString("delivery_id"));
        }
      }
    }
    for (String deliveryId : deliveryIds) {
      try (var delete =
          connection.prepareStatement("DELETE FROM channel_delivery_parts WHERE delivery_id = ?")) {
        delete.setString(1, deliveryId);
        if (delete.executeUpdate() < 1) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
      }
      try (var update =
          connection.prepareStatement(
              """
              UPDATE channel_deliveries
              SET payload_pruned = 1, revision = revision + 1, updated_at = ?
              WHERE delivery_id = ? AND state IN ('DELIVERED', 'FAILED')
                AND payload_pruned = 0 AND updated_at < ?
              """)) {
        update.setString(1, command.cleanedAt().toString());
        update.setString(2, deliveryId);
        update.setString(3, command.cutoff().toString());
        if (update.executeUpdate() != 1) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
      }
    }
    return deliveryIds.size();
  }

  private static List<DeliveryRecoveryCandidate> findDeliveryRecoveryCandidates(
      Connection connection, ChannelLedgerCommand.Recover command, int limit) throws SQLException {
    var candidates = new ArrayList<DeliveryRecoveryCandidate>();
    try (var statement =
        connection.prepareStatement(
            """
            SELECT d.delivery_id, d.next_part_index, d.revision,
              p.attempt_count, p.state AS part_state,
              a.outcome AS attempt_outcome
            FROM channel_deliveries d
            LEFT JOIN channel_delivery_parts p
              ON p.delivery_id = d.delivery_id AND p.part_index = d.next_part_index
            LEFT JOIN channel_delivery_attempts a
              ON a.delivery_id = p.delivery_id AND a.part_index = p.part_index
                AND a.attempt_number = p.attempt_count
            WHERE d.channel = ? AND d.instance_id = ?
              AND d.state = 'DELIVERING' AND d.owner_id <> ?
            ORDER BY d.updated_at ASC, d.delivery_id ASC
            LIMIT ?
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.newOwnerId());
      statement.setInt(4, Math.addExact(limit, 1));
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          DeliveryPartState partState =
              rows.getString("part_state") == null
                  ? null
                  : DeliveryPartState.valueOf(rows.getString("part_state"));
          DeliveryAttemptOutcome attemptOutcome =
              rows.getString("attempt_outcome") == null
                  ? null
                  : DeliveryAttemptOutcome.valueOf(rows.getString("attempt_outcome"));
          candidates.add(
              new DeliveryRecoveryCandidate(
                  rows.getString("delivery_id"),
                  exactNonNegativeInt(rows, "next_part_index"),
                  exactNonNegativeLong(rows, "revision"),
                  rows.getString("part_state") == null
                      ? 0
                      : exactNonNegativeInt(rows, "attempt_count"),
                  partState,
                  attemptOutcome));
        }
      } catch (IllegalArgumentException | NullPointerException exception) {
        throw new SQLException("invalid delivery recovery state", exception);
      }
    }
    return List.copyOf(candidates);
  }

  private static void recoverDelivery(
      Connection connection, DeliveryRecoveryCandidate candidate, Instant recoveredAt)
      throws SQLException {
    if (candidate.attemptOutcome() == DeliveryAttemptOutcome.STARTED) {
      try (var statement =
          connection.prepareStatement(
              """
              UPDATE channel_delivery_attempts
              SET completed_at = ?, outcome = 'UNKNOWN', error_code = 'RECOVERY_OUTCOME_UNKNOWN'
              WHERE delivery_id = ? AND part_index = ? AND attempt_number = ?
                AND outcome = 'STARTED' AND completed_at IS NULL
              """)) {
        statement.setString(1, recoveredAt.toString());
        statement.setString(2, candidate.deliveryId());
        statement.setInt(3, candidate.partIndex());
        statement.setInt(4, candidate.attemptCount());
        if (statement.executeUpdate() != 1) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
      }
    }
    if (candidate.partState() == DeliveryPartState.IN_FLIGHT) {
      try (var statement =
          connection.prepareStatement(
              """
              UPDATE channel_delivery_parts
              SET state = 'UNKNOWN', next_attempt_at = NULL, remote_message_id = NULL,
                last_error_code = 'RECOVERY_OUTCOME_UNKNOWN', updated_at = ?
              WHERE delivery_id = ? AND part_index = ? AND state = 'IN_FLIGHT'
                AND attempt_count = ?
              """)) {
        statement.setString(1, recoveredAt.toString());
        statement.setString(2, candidate.deliveryId());
        statement.setInt(3, candidate.partIndex());
        statement.setInt(4, candidate.attemptCount());
        if (statement.executeUpdate() != 1) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
      }
    }
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_deliveries
            SET state = 'UNKNOWN', owner_id = NULL, lease_expires_at = NULL,
              last_error_code = 'RECOVERY_OUTCOME_UNKNOWN',
              revision = ?, updated_at = ?
            WHERE delivery_id = ? AND state = 'DELIVERING' AND revision = ?
            """)) {
      statement.setLong(1, increment(candidate.revision()));
      statement.setString(2, recoveredAt.toString());
      statement.setString(3, candidate.deliveryId());
      statement.setLong(4, candidate.revision());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
  }

  private ChannelLedgerResult.Terminal recordTerminalInTransaction(
      Connection connection, ChannelLedgerCommand.RecordTerminal command) throws SQLException {
    DeliveryEnvelope envelope = command.delivery();
    Optional<StoredDelivery> existing = findDelivery(connection, envelope);
    StoredTurnClaim claim = findTurnClaim(connection, command.instance(), command.turnId());
    if (existing.isPresent()) {
      StoredDelivery stored = existing.orElseThrow();
      requireMatchingDelivery(connection, stored, envelope);
      if (claim.state() != TurnClaimState.TERMINAL_RECORDED) {
        throw ChannelLedgerRepositoryException.idempotencyConflict();
      }
      return new ChannelLedgerResult.Terminal(
          ChannelLedgerResult.TerminalStatus.REPLAYED, stored.deliveryId(), claim.revision());
    }
    if (claim.state() != TurnClaimState.RUNNING || claim.revision() != command.expectedRevision()) {
      throw ChannelLedgerRepositoryException.staleWrite();
    }

    insertDelivery(connection, envelope, command.recordedAt());
    long revision = increment(claim.revision());
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_turn_claims
            SET state = 'TERMINAL_RECORDED', owner_id = NULL, lease_expires_at = NULL,
              revision = ?, updated_at = ?
            WHERE channel = ? AND instance_id = ? AND turn_id = ?
              AND state = 'RUNNING' AND revision = ?
            """)) {
      statement.setLong(1, revision);
      statement.setString(2, command.recordedAt().toString());
      statement.setString(3, command.instance().channel());
      statement.setString(4, command.instance().value());
      statement.setString(5, command.turnId());
      statement.setLong(6, claim.revision());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    faults.hit(ChannelLedgerFaultPoint.TERMINAL_RECORDED_BEFORE_COMMIT);
    return new ChannelLedgerResult.Terminal(
        ChannelLedgerResult.TerminalStatus.CREATED, envelope.deliveryId(), revision);
  }

  private ChannelLedgerResult.Event recordFeedbackInTransaction(
      Connection connection, ChannelLedgerCommand.RecordEvent command) throws SQLException {
    DeliveryEnvelope envelope = command.feedback();
    Optional<StoredInboxEvent> existingEvent = findInboxEvent(connection, command);
    Cursor cursor = findCursor(connection, command.instance()).orElse(Cursor.empty());
    if (existingEvent.isPresent()) {
      StoredInboxEvent stored = existingEvent.orElseThrow();
      if (!stored.matchesFeedback(command)) {
        throw ChannelLedgerRepositoryException.idempotencyConflict();
      }
      StoredDelivery delivery =
          findDelivery(connection, envelope)
              .orElseThrow(ChannelLedgerRepositoryException::idempotencyConflict);
      requireMatchingDelivery(connection, delivery, envelope);
      if (!cursor.persisted() || cursor.nextSequence() <= command.externalSequence()) {
        throw ChannelLedgerRepositoryException.idempotencyConflict();
      }
      return feedbackResult(delivery.deliveryId(), cursor);
    }
    if (command.externalSequence() < cursor.nextSequence()
        || findDelivery(connection, envelope).isPresent()) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }

    insertDelivery(connection, envelope, command.recordedAt());
    insertFeedbackEvent(connection, command);
    Cursor advanced = advanceCursor(connection, command, cursor);
    faults.hit(ChannelLedgerFaultPoint.TERMINAL_RECORDED_BEFORE_COMMIT);
    return feedbackResult(envelope.deliveryId(), advanced);
  }

  private static ChannelLedgerResult.Event feedbackResult(String deliveryId, Cursor cursor) {
    return new ChannelLedgerResult.Event(
        ChannelLedgerResult.InboxStatus.FEEDBACK_QUEUED,
        null,
        cursor.revision(),
        deliveryId,
        cursor.nextSequence());
  }

  private static StoredTurnClaim findTurnClaim(
      Connection connection, ChannelInstanceId instance, String turnId) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT state, revision
            FROM channel_turn_claims
            WHERE channel = ? AND instance_id = ? AND turn_id = ?
            """)) {
      statement.setString(1, instance.channel());
      statement.setString(2, instance.value());
      statement.setString(3, turnId);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
        StoredTurnClaim claim;
        try {
          claim =
              new StoredTurnClaim(
                  TurnClaimState.valueOf(rows.getString("state")),
                  exactNonNegativeLong(rows, "revision"));
        } catch (IllegalArgumentException | NullPointerException exception) {
          throw new SQLException("invalid terminal claim", exception);
        }
        if (rows.next()) {
          throw new SQLException("duplicate terminal claim");
        }
        return claim;
      }
    }
  }

  private static Optional<StoredDelivery> findDelivery(
      Connection connection, DeliveryEnvelope envelope) throws SQLException {
    var matches = new ArrayList<StoredDelivery>();
    try (var statement =
        connection.prepareStatement(
            """
            SELECT delivery_id, channel, instance_id, target_id, source_kind, correlation_id,
              message_type, payload_fingerprint, state, part_count, next_part_index, payload_pruned,
              owner_id, lease_expires_at, last_error_code, revision
            FROM channel_deliveries
            WHERE delivery_id = ? OR (
              channel = ? AND instance_id = ? AND source_kind = ? AND correlation_id = ?
            )
            ORDER BY delivery_id
            """)) {
      statement.setString(1, envelope.deliveryId());
      statement.setString(2, envelope.instance().channel());
      statement.setString(3, envelope.instance().value());
      statement.setString(4, envelope.sourceKind().name());
      statement.setString(5, envelope.correlationId());
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          matches.add(readDelivery(rows));
        }
      }
    }
    if (matches.size() > 1) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    return matches.stream().findFirst();
  }

  private static void requireMatchingDelivery(
      Connection connection, StoredDelivery stored, DeliveryEnvelope envelope) throws SQLException {
    if (!stored.deliveryId().equals(envelope.deliveryId())
        || !stored.instance().equals(envelope.instance())
        || !stored.targetId().equals(envelope.targetId())
        || !stored.sourceKind().equals(envelope.sourceKind().name())
        || !stored.correlationId().equals(envelope.correlationId())
        || !stored.messageType().equals(envelope.messageType().name())
        || !stored.payloadFingerprint().equals(envelope.payloadFingerprint())
        || stored.partCount() != envelope.parts().size()) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    if (stored.payloadPruned()) {
      return;
    }
    List<StoredPart> parts = findParts(connection, stored.deliveryId());
    if (parts.size() != envelope.parts().size()) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    for (int index = 0; index < parts.size(); index++) {
      StoredPart storedPart = parts.get(index);
      DeliveryPartPayload expected = envelope.parts().get(index);
      if (storedPart.partIndex() != expected.index()
          || !storedPart.payloadText().equals(expected.payload())
          || !storedPart.payloadFingerprint().equals(expected.payloadFingerprint())) {
        throw ChannelLedgerRepositoryException.idempotencyConflict();
      }
    }
  }

  private static void insertDelivery(
      Connection connection, DeliveryEnvelope envelope, Instant recordedAt) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO channel_deliveries (
              delivery_id, channel, instance_id, target_id, source_kind, correlation_id,
              message_type, payload_fingerprint, state, part_count, next_part_index, payload_pruned,
              owner_id, lease_expires_at, last_error_code, revision, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 0, 0, NULL, NULL, NULL, 0, ?, ?)
            """)) {
      statement.setString(1, envelope.deliveryId());
      statement.setString(2, envelope.instance().channel());
      statement.setString(3, envelope.instance().value());
      statement.setString(4, envelope.targetId());
      statement.setString(5, envelope.sourceKind().name());
      statement.setString(6, envelope.correlationId());
      statement.setString(7, envelope.messageType().name());
      statement.setString(8, envelope.payloadFingerprint());
      statement.setInt(9, envelope.parts().size());
      statement.setString(10, recordedAt.toString());
      statement.setString(11, recordedAt.toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("channel delivery insert count is not one");
      }
    }
    for (DeliveryPartPayload part : envelope.parts()) {
      try (var statement =
          connection.prepareStatement(
              """
              INSERT INTO channel_delivery_parts (
                delivery_id, part_index, payload_text, payload_fingerprint, state,
                attempt_count, next_attempt_at, remote_message_id, last_error_code, updated_at
              ) VALUES (?, ?, ?, ?, 'PENDING', 0, NULL, NULL, NULL, ?)
              """)) {
        statement.setString(1, envelope.deliveryId());
        statement.setInt(2, part.index());
        statement.setString(3, part.payload());
        statement.setString(4, part.payloadFingerprint());
        statement.setString(5, recordedAt.toString());
        if (statement.executeUpdate() != 1) {
          throw new SQLException("channel delivery part insert count is not one");
        }
      }
    }
  }

  private static Optional<StoredInboxEvent> findInboxEvent(
      Connection connection, ChannelLedgerCommand.RecordEvent command) throws SQLException {
    var matches = new ArrayList<StoredInboxEvent>();
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
          matches.add(
              new StoredInboxEvent(
                  rows.getString("external_event_id"),
                  exactNonNegativeLong(rows, "external_sequence"),
                  rows.getString("event_fingerprint"),
                  rows.getString("decision"),
                  rows.getString("turn_id")));
        }
      }
    }
    if (matches.size() > 1) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    return matches.stream().findFirst();
  }

  private static void insertFeedbackEvent(
      Connection connection, ChannelLedgerCommand.RecordEvent command) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO channel_inbox_events (
              channel, instance_id, external_event_id, external_sequence,
              event_fingerprint, decision, turn_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'FEEDBACK_QUEUED', NULL, ?, ?)
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.externalEventId());
      statement.setLong(4, command.externalSequence());
      statement.setString(5, command.eventFingerprint());
      statement.setString(6, command.recordedAt().toString());
      statement.setString(7, command.recordedAt().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("feedback event insert count is not one");
      }
    }
  }

  private Optional<ChannelLedgerResult.DeliveryWork> claimNextInTransaction(
      Connection connection, ChannelLedgerCommand.ClaimDelivery command) throws SQLException {
    Optional<DueDelivery> found = findDueDelivery(connection, command);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    DueDelivery due = found.orElseThrow();
    long revision = increment(due.revision());
    int attemptNumber = Math.addExact(due.attemptCount(), 1);
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_deliveries
            SET state = 'DELIVERING', owner_id = ?, lease_expires_at = ?,
              revision = ?, updated_at = ?
            WHERE delivery_id = ? AND state = 'PENDING' AND revision = ?
            """)) {
      statement.setString(1, command.ownerId());
      statement.setString(2, command.leaseExpiresAt().toString());
      statement.setLong(3, revision);
      statement.setString(4, command.claimedAt().toString());
      statement.setString(5, due.deliveryId());
      statement.setLong(6, due.revision());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_delivery_parts
            SET state = 'IN_FLIGHT', attempt_count = ?, next_attempt_at = NULL,
              remote_message_id = NULL, last_error_code = NULL, updated_at = ?
            WHERE delivery_id = ? AND part_index = ? AND state = ? AND attempt_count = ?
            """)) {
      statement.setInt(1, attemptNumber);
      statement.setString(2, command.claimedAt().toString());
      statement.setString(3, due.deliveryId());
      statement.setInt(4, due.partIndex());
      statement.setString(5, due.partState().name());
      statement.setInt(6, due.attemptCount());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO channel_delivery_attempts (
              delivery_id, part_index, attempt_number, started_at,
              completed_at, outcome, remote_message_id, error_code
            ) VALUES (?, ?, ?, ?, NULL, 'STARTED', NULL, NULL)
            """)) {
      statement.setString(1, due.deliveryId());
      statement.setInt(2, due.partIndex());
      statement.setInt(3, attemptNumber);
      statement.setString(4, command.claimedAt().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("delivery attempt insert count is not one");
      }
    }
    faults.hit(ChannelLedgerFaultPoint.DELIVERY_CLAIMED_BEFORE_COMMIT);
    return Optional.of(
        new ChannelLedgerResult.DeliveryWork(
            due.instance(),
            due.deliveryId(),
            due.targetId(),
            due.partIndex(),
            due.payloadText(),
            attemptNumber,
            command.ownerId(),
            revision,
            command.claimedAt()));
  }

  private static Optional<DueDelivery> findDueDelivery(
      Connection connection, ChannelLedgerCommand.ClaimDelivery command) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT d.delivery_id, d.channel, d.instance_id, d.target_id,
              d.next_part_index, d.revision,
              p.payload_text, p.state AS part_state, p.attempt_count
            FROM channel_deliveries d
            JOIN channel_delivery_parts p
              ON p.delivery_id = d.delivery_id AND p.part_index = d.next_part_index
            WHERE d.channel = ? AND d.instance_id = ? AND d.state = 'PENDING'
              AND d.payload_pruned = 0 AND p.attempt_count < 2
              AND (
                p.state = 'PENDING'
                OR (p.state = 'RETRY_WAIT' AND p.next_attempt_at <= ?)
              )
            ORDER BY COALESCE(p.next_attempt_at, d.created_at) ASC,
              d.created_at ASC, d.delivery_id ASC
            LIMIT 1
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.claimedAt().toString());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        DueDelivery due;
        try {
          ChannelInstanceId instance =
              new ChannelInstanceId(rows.getString("channel"), rows.getString("instance_id"));
          due =
              new DueDelivery(
                  instance,
                  rows.getString("delivery_id"),
                  rows.getString("target_id"),
                  exactNonNegativeInt(rows, "next_part_index"),
                  rows.getString("payload_text"),
                  DeliveryPartState.valueOf(rows.getString("part_state")),
                  exactNonNegativeInt(rows, "attempt_count"),
                  exactNonNegativeLong(rows, "revision"));
        } catch (IllegalArgumentException | NullPointerException exception) {
          throw new SQLException("invalid due delivery", exception);
        }
        if (rows.next()) {
          throw new SQLException("multiple due delivery rows");
        }
        return Optional.of(due);
      }
    }
  }

  private ChannelLedgerResult.DeliveryUpdate recordOutcomeInTransaction(
      Connection connection, ChannelLedgerCommand.RecordDeliveryOutcome command)
      throws SQLException {
    StoredDelivery delivery = findDeliveryById(connection, command.deliveryId());
    StoredPart part = findPart(connection, command.deliveryId(), command.partIndex());
    StoredAttempt attempt =
        findAttempt(connection, command.deliveryId(), command.partIndex(), command.attemptNumber());
    if (attempt.outcome() != DeliveryAttemptOutcome.STARTED) {
      requireMatchingOutcomeReplay(delivery, part, attempt, command);
      return deliveryUpdate(delivery, part);
    }
    if (delivery.state() != DeliveryState.DELIVERING
        || !Objects.equals(delivery.ownerId(), command.ownerId())
        || delivery.nextPartIndex() != command.partIndex()
        || part.state() != DeliveryPartState.IN_FLIGHT
        || part.attemptCount() != command.attemptNumber()) {
      throw ChannelLedgerRepositoryException.staleWrite();
    }

    completeAttempt(connection, command);
    OutcomeProjection projection = projectOutcome(delivery, part, command);
    updatePart(connection, part, projection, command.completedAt());
    StoredDelivery updated =
        updateDelivery(connection, delivery, projection, command.completedAt());
    faults.hit(ChannelLedgerFaultPoint.DELIVERY_OUTCOME_BEFORE_COMMIT);
    return new ChannelLedgerResult.DeliveryUpdate(
        projection.deliveryState(),
        projection.partState(),
        projection.nextPartIndex(),
        updated.revision());
  }

  private static void completeAttempt(
      Connection connection, ChannelLedgerCommand.RecordDeliveryOutcome command)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_delivery_attempts
            SET completed_at = ?, outcome = ?, remote_message_id = ?, error_code = ?
            WHERE delivery_id = ? AND part_index = ? AND attempt_number = ?
              AND outcome = 'STARTED' AND completed_at IS NULL
            """)) {
      statement.setString(1, command.completedAt().toString());
      statement.setString(2, command.outcome().name());
      setNullable(statement, 3, command.remoteMessageId());
      setNullable(statement, 4, emptyToNull(command.errorCode()));
      statement.setString(5, command.deliveryId());
      statement.setInt(6, command.partIndex());
      statement.setInt(7, command.attemptNumber());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
  }

  private static OutcomeProjection projectOutcome(
      StoredDelivery delivery,
      StoredPart part,
      ChannelLedgerCommand.RecordDeliveryOutcome command) {
    return switch (command.outcome()) {
      case SUCCEEDED -> {
        int nextPart = Math.addExact(part.partIndex(), 1);
        DeliveryState state =
            nextPart == delivery.partCount() ? DeliveryState.DELIVERED : DeliveryState.PENDING;
        yield new OutcomeProjection(
            state, DeliveryPartState.DELIVERED, nextPart, null, command.remoteMessageId(), null);
      }
      case RETRYABLE_REJECTED -> {
        if (command.attemptNumber() == 1) {
          yield new OutcomeProjection(
              DeliveryState.PENDING,
              DeliveryPartState.RETRY_WAIT,
              delivery.nextPartIndex(),
              command.retryAt(),
              null,
              command.errorCode());
        }
        yield new OutcomeProjection(
            DeliveryState.FAILED,
            DeliveryPartState.FAILED,
            delivery.nextPartIndex(),
            null,
            null,
            RETRY_BUDGET_EXCEEDED);
      }
      case PERMANENT_REJECTED ->
          new OutcomeProjection(
              DeliveryState.FAILED,
              DeliveryPartState.FAILED,
              delivery.nextPartIndex(),
              null,
              null,
              command.errorCode());
      case UNKNOWN ->
          new OutcomeProjection(
              DeliveryState.UNKNOWN,
              DeliveryPartState.UNKNOWN,
              delivery.nextPartIndex(),
              null,
              null,
              command.errorCode());
      case STARTED -> throw ChannelLedgerRepositoryException.operationFailed(null);
    };
  }

  private static void updatePart(
      Connection connection, StoredPart part, OutcomeProjection projection, Instant completedAt)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_delivery_parts
            SET state = ?, next_attempt_at = ?, remote_message_id = ?,
              last_error_code = ?, updated_at = ?
            WHERE delivery_id = ? AND part_index = ?
              AND state = 'IN_FLIGHT' AND attempt_count = ?
            """)) {
      statement.setString(1, projection.partState().name());
      setNullable(
          statement,
          2,
          projection.nextAttemptAt() == null ? null : projection.nextAttemptAt().toString());
      setNullable(statement, 3, projection.remoteMessageId());
      setNullable(statement, 4, projection.errorCode());
      statement.setString(5, completedAt.toString());
      statement.setString(6, part.deliveryId());
      statement.setInt(7, part.partIndex());
      statement.setInt(8, part.attemptCount());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
  }

  private static StoredDelivery updateDelivery(
      Connection connection,
      StoredDelivery delivery,
      OutcomeProjection projection,
      Instant completedAt)
      throws SQLException {
    long revision = increment(delivery.revision());
    String headerError =
        projection.deliveryState() == DeliveryState.FAILED
                || projection.deliveryState() == DeliveryState.UNKNOWN
            ? projection.errorCode()
            : null;
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_deliveries
            SET state = ?, next_part_index = ?, owner_id = NULL, lease_expires_at = NULL,
              last_error_code = ?, revision = ?, updated_at = ?
            WHERE delivery_id = ? AND state = 'DELIVERING' AND revision = ?
            """)) {
      statement.setString(1, projection.deliveryState().name());
      statement.setInt(2, projection.nextPartIndex());
      setNullable(statement, 3, headerError);
      statement.setLong(4, revision);
      statement.setString(5, completedAt.toString());
      statement.setString(6, delivery.deliveryId());
      statement.setLong(7, delivery.revision());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    return delivery.withOutcome(
        projection.deliveryState(), projection.nextPartIndex(), headerError, revision);
  }

  private static StoredDelivery findDeliveryById(Connection connection, String deliveryId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT delivery_id, channel, instance_id, target_id, source_kind, correlation_id,
              message_type, payload_fingerprint, state, part_count, next_part_index, payload_pruned,
              owner_id, lease_expires_at, last_error_code, revision
            FROM channel_deliveries WHERE delivery_id = ?
            """)) {
      statement.setString(1, deliveryId);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
        StoredDelivery delivery = readDelivery(rows);
        if (rows.next()) {
          throw new SQLException("duplicate delivery id");
        }
        return delivery;
      }
    }
  }

  private static StoredDelivery readDelivery(ResultSet rows) throws SQLException {
    try {
      return new StoredDelivery(
          rows.getString("delivery_id"),
          new ChannelInstanceId(rows.getString("channel"), rows.getString("instance_id")),
          rows.getString("target_id"),
          rows.getString("source_kind"),
          rows.getString("correlation_id"),
          rows.getString("message_type"),
          rows.getString("payload_fingerprint"),
          DeliveryState.valueOf(rows.getString("state")),
          exactNonNegativeInt(rows, "part_count"),
          exactNonNegativeInt(rows, "next_part_index"),
          rows.getInt("payload_pruned") == 1,
          rows.getString("owner_id"),
          rows.getString("lease_expires_at"),
          rows.getString("last_error_code"),
          exactNonNegativeLong(rows, "revision"));
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new SQLException("invalid delivery", exception);
    }
  }

  private static List<StoredPart> findParts(Connection connection, String deliveryId)
      throws SQLException {
    var parts = new ArrayList<StoredPart>();
    try (var statement =
        connection.prepareStatement(
            """
            SELECT delivery_id, part_index, payload_text, payload_fingerprint,
              state, attempt_count, next_attempt_at, remote_message_id, last_error_code
            FROM channel_delivery_parts
            WHERE delivery_id = ? ORDER BY part_index
            """)) {
      statement.setString(1, deliveryId);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          parts.add(readPart(rows));
        }
      }
    }
    return List.copyOf(parts);
  }

  private static StoredPart findPart(Connection connection, String deliveryId, int partIndex)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT delivery_id, part_index, payload_text, payload_fingerprint,
              state, attempt_count, next_attempt_at, remote_message_id, last_error_code
            FROM channel_delivery_parts
            WHERE delivery_id = ? AND part_index = ?
            """)) {
      statement.setString(1, deliveryId);
      statement.setInt(2, partIndex);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
        StoredPart part = readPart(rows);
        if (rows.next()) {
          throw new SQLException("duplicate delivery part");
        }
        return part;
      }
    }
  }

  private static StoredPart readPart(ResultSet rows) throws SQLException {
    try {
      return new StoredPart(
          rows.getString("delivery_id"),
          exactNonNegativeInt(rows, "part_index"),
          rows.getString("payload_text"),
          rows.getString("payload_fingerprint"),
          DeliveryPartState.valueOf(rows.getString("state")),
          exactNonNegativeInt(rows, "attempt_count"),
          rows.getString("next_attempt_at"),
          rows.getString("remote_message_id"),
          rows.getString("last_error_code"));
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new SQLException("invalid delivery part", exception);
    }
  }

  private static StoredAttempt findAttempt(
      Connection connection, String deliveryId, int partIndex, int attemptNumber)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT outcome, completed_at, remote_message_id, error_code
            FROM channel_delivery_attempts
            WHERE delivery_id = ? AND part_index = ? AND attempt_number = ?
            """)) {
      statement.setString(1, deliveryId);
      statement.setInt(2, partIndex);
      statement.setInt(3, attemptNumber);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw ChannelLedgerRepositoryException.staleWrite();
        }
        StoredAttempt attempt;
        try {
          attempt =
              new StoredAttempt(
                  DeliveryAttemptOutcome.valueOf(rows.getString("outcome")),
                  rows.getString("completed_at"),
                  rows.getString("remote_message_id"),
                  rows.getString("error_code"));
        } catch (IllegalArgumentException | NullPointerException exception) {
          throw new SQLException("invalid delivery attempt", exception);
        }
        if (rows.next()) {
          throw new SQLException("duplicate delivery attempt");
        }
        return attempt;
      }
    }
  }

  private static void requireMatchingOutcomeReplay(
      StoredDelivery delivery,
      StoredPart part,
      StoredAttempt attempt,
      ChannelLedgerCommand.RecordDeliveryOutcome command) {
    String expectedError = emptyToNull(command.errorCode());
    if (attempt.outcome() != command.outcome()
        || !Objects.equals(attempt.completedAt(), command.completedAt().toString())
        || !Objects.equals(attempt.remoteMessageId(), command.remoteMessageId())
        || !Objects.equals(attempt.errorCode(), expectedError)) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    if (command.outcome() == DeliveryAttemptOutcome.RETRYABLE_REJECTED
        && command.attemptNumber() == 1
        && !Objects.equals(part.nextAttemptAt(), command.retryAt().toString())) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    if (command.outcome() == DeliveryAttemptOutcome.SUCCEEDED
        && !Objects.equals(part.remoteMessageId(), command.remoteMessageId())) {
      throw ChannelLedgerRepositoryException.idempotencyConflict();
    }
    if (delivery.state() == DeliveryState.DELIVERING
        || part.state() == DeliveryPartState.IN_FLIGHT) {
      throw ChannelLedgerRepositoryException.operationFailed(null);
    }
  }

  private static ChannelLedgerResult.DeliveryUpdate deliveryUpdate(
      StoredDelivery delivery, StoredPart part) {
    return new ChannelLedgerResult.DeliveryUpdate(
        delivery.state(), part.state(), delivery.nextPartIndex(), delivery.revision());
  }

  private static Optional<Cursor> findCursor(Connection connection, ChannelInstanceId instance)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT next_sequence, revision FROM channel_cursors
            WHERE channel = ? AND instance_id = ?
            """)) {
      statement.setString(1, instance.channel());
      statement.setString(2, instance.value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        Cursor cursor =
            new Cursor(
                exactNonNegativeLong(rows, "next_sequence"),
                exactNonNegativeLong(rows, "revision"),
                true);
        if (rows.next()) {
          throw new SQLException("duplicate feedback cursor");
        }
        return Optional.of(cursor);
      }
    }
  }

  private static Cursor advanceCursor(
      Connection connection, ChannelLedgerCommand.RecordEvent command, Cursor cursor)
      throws SQLException {
    long next = Math.addExact(command.externalSequence(), 1L);
    if (!cursor.persisted()) {
      try (var statement =
          connection.prepareStatement(
              """
              INSERT INTO channel_cursors (
                channel, instance_id, next_sequence, revision, updated_at
              ) VALUES (?, ?, ?, 0, ?)
              """)) {
        statement.setString(1, command.instance().channel());
        statement.setString(2, command.instance().value());
        statement.setLong(3, next);
        statement.setString(4, command.recordedAt().toString());
        if (statement.executeUpdate() != 1) {
          throw new SQLException("feedback cursor insert count is not one");
        }
      }
      return new Cursor(next, 0, true);
    }
    long revision = increment(cursor.revision());
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE channel_cursors
            SET next_sequence = ?, revision = ?, updated_at = ?
            WHERE channel = ? AND instance_id = ?
              AND next_sequence = ? AND revision = ?
            """)) {
      statement.setLong(1, next);
      statement.setLong(2, revision);
      statement.setString(3, command.recordedAt().toString());
      statement.setString(4, command.instance().channel());
      statement.setString(5, command.instance().value());
      statement.setLong(6, cursor.nextSequence());
      statement.setLong(7, cursor.revision());
      if (statement.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.staleWrite();
      }
    }
    return new Cursor(next, revision, true);
  }

  private static long countDeliveries(
      Connection connection, ChannelInstanceId instance, String predicate) throws SQLException {
    String sql =
        "SELECT COUNT(*) FROM channel_deliveries"
            + " WHERE channel = ? AND instance_id = ? AND "
            + predicate;
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, instance.channel());
      statement.setString(2, instance.value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("delivery count missing");
        }
        long count = exactNonNegativeLong(rows, 1);
        if (rows.next()) {
          throw new SQLException("duplicate delivery count");
        }
        return count;
      }
    }
  }

  private static void setNullable(java.sql.PreparedStatement statement, int index, String value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, value);
    }
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static int exactNonNegativeInt(ResultSet rows, String column) throws SQLException {
    long value = exactNonNegativeLong(rows, column);
    if (value > Integer.MAX_VALUE) {
      throw new SQLException("delivery integer is out of range");
    }
    return Math.toIntExact(value);
  }

  private static long exactNonNegativeLong(ResultSet rows, String column) throws SQLException {
    long value = rows.getLong(column);
    if (rows.wasNull() || value < 0) {
      throw new SQLException("invalid delivery integer");
    }
    return value;
  }

  private static long exactNonNegativeLong(ResultSet rows, int column) throws SQLException {
    long value = rows.getLong(column);
    if (rows.wasNull() || value < 0) {
      throw new SQLException("invalid delivery integer");
    }
    return value;
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

  private record StoredTurnClaim(TurnClaimState state, long revision) {}

  private record StoredInboxEvent(
      String externalEventId,
      long externalSequence,
      String eventFingerprint,
      String decision,
      String turnId) {
    private boolean matchesFeedback(ChannelLedgerCommand.RecordEvent command) {
      return externalEventId.equals(command.externalEventId())
          && externalSequence == command.externalSequence()
          && eventFingerprint.equals(command.eventFingerprint())
          && decision.equals("FEEDBACK_QUEUED")
          && turnId == null;
    }
  }

  private record StoredDelivery(
      String deliveryId,
      ChannelInstanceId instance,
      String targetId,
      String sourceKind,
      String correlationId,
      String messageType,
      String payloadFingerprint,
      DeliveryState state,
      int partCount,
      int nextPartIndex,
      boolean payloadPruned,
      String ownerId,
      String leaseExpiresAt,
      String lastErrorCode,
      long revision) {
    private StoredDelivery withOutcome(
        DeliveryState newState, int newNextPartIndex, String newError, long newRevision) {
      return new StoredDelivery(
          deliveryId,
          instance,
          targetId,
          sourceKind,
          correlationId,
          messageType,
          payloadFingerprint,
          newState,
          partCount,
          newNextPartIndex,
          payloadPruned,
          null,
          null,
          newError,
          newRevision);
    }
  }

  private record StoredPart(
      String deliveryId,
      int partIndex,
      String payloadText,
      String payloadFingerprint,
      DeliveryPartState state,
      int attemptCount,
      String nextAttemptAt,
      String remoteMessageId,
      String lastErrorCode) {}

  private record StoredAttempt(
      DeliveryAttemptOutcome outcome,
      String completedAt,
      String remoteMessageId,
      String errorCode) {}

  private record DueDelivery(
      ChannelInstanceId instance,
      String deliveryId,
      String targetId,
      int partIndex,
      String payloadText,
      DeliveryPartState partState,
      int attemptCount,
      long revision) {}

  private record OutcomeProjection(
      DeliveryState deliveryState,
      DeliveryPartState partState,
      int nextPartIndex,
      Instant nextAttemptAt,
      String remoteMessageId,
      String errorCode) {}

  record RecoverySlice(int processed, boolean remaining) {}

  private record DeliveryRecoveryCandidate(
      String deliveryId,
      int partIndex,
      long revision,
      int attemptCount,
      DeliveryPartState partState,
      DeliveryAttemptOutcome attemptOutcome) {}

  private record Cursor(long nextSequence, long revision, boolean persisted) {
    private static Cursor empty() {
      return new Cursor(0, 0, false);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }
}
