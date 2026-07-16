package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

public final class JdbcChannelLedger implements ChannelLedgerPort {
  private final ChannelLedgerSchemaInitializer schema;
  private final ChannelLedgerFaultInjector faults;

  public JdbcChannelLedger(ChannelLedgerSchemaInitializer schema) {
    this(schema, ignored -> {});
  }

  JdbcChannelLedger(ChannelLedgerSchemaInitializer schema, ChannelLedgerFaultInjector faults) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.faults = Objects.requireNonNull(faults, "faults");
  }

  @Override
  public ChannelLedgerResult.Event recordEvent(ChannelLedgerCommand.RecordEvent command) {
    Objects.requireNonNull(command, "command");
    if (command.kind() != InboxEventKind.IGNORED && command.kind() != InboxEventKind.CONTROL) {
      throw ChannelLedgerRepositoryException.operationFailed(null);
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
    throw ChannelLedgerRepositoryException.operationFailed(null);
  }

  @Override
  public ChannelLedgerResult.Terminal recordTerminal(ChannelLedgerCommand.RecordTerminal command) {
    throw ChannelLedgerRepositoryException.operationFailed(null);
  }

  @Override
  public Optional<ChannelLedgerResult.DeliveryWork> claimNextDelivery(
      ChannelLedgerCommand.ClaimDelivery command) {
    throw ChannelLedgerRepositoryException.operationFailed(null);
  }

  @Override
  public ChannelLedgerResult.DeliveryUpdate recordDeliveryOutcome(
      ChannelLedgerCommand.RecordDeliveryOutcome command) {
    throw ChannelLedgerRepositoryException.operationFailed(null);
  }

  @Override
  public ChannelLedgerResult.Recovery recover(ChannelLedgerCommand.Recover command) {
    throw ChannelLedgerRepositoryException.operationFailed(null);
  }

  @Override
  public ChannelLedgerResult.Cleanup cleanup(ChannelLedgerCommand.Cleanup command) {
    throw ChannelLedgerRepositoryException.operationFailed(null);
  }

  @Override
  public ChannelLedgerResult.Snapshot snapshot(ChannelInstanceId instance) {
    Objects.requireNonNull(instance, "instance");
    try (var connection = schema.openConnection()) {
      CursorState cursor = findCursor(connection, instance).orElse(CursorState.empty());
      return new ChannelLedgerResult.Snapshot(cursor.nextSequence(), 0, 0, 0, "");
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw ChannelLedgerRepositoryException.operationFailed(exception);
    }
  }

  private ChannelLedgerResult.Event recordEventInTransaction(
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

    insertEvent(connection, command);
    CursorState advanced = advanceCursor(connection, command, cursor);
    faults.hit(ChannelLedgerFaultPoint.EVENT_RECORDED_BEFORE_COMMIT);
    return eventResult(advanced);
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

  private static void insertEvent(Connection connection, ChannelLedgerCommand.RecordEvent command)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO channel_inbox_events (
              channel, instance_id, external_event_id, external_sequence,
              event_fingerprint, decision, turn_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
            """)) {
      statement.setString(1, command.instance().channel());
      statement.setString(2, command.instance().value());
      statement.setString(3, command.externalEventId());
      statement.setLong(4, command.externalSequence());
      statement.setString(5, command.eventFingerprint());
      statement.setString(6, decision(command.kind()));
      statement.setString(7, command.recordedAt().toString());
      statement.setString(8, command.recordedAt().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("channel event insert count is not one");
      }
    }
  }

  private static CursorState advanceCursor(
      Connection connection, ChannelLedgerCommand.RecordEvent command, CursorState cursor)
      throws SQLException {
    long nextSequence;
    try {
      nextSequence = Math.addExact(command.externalSequence(), 1L);
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
        statement.setString(1, command.instance().channel());
        statement.setString(2, command.instance().value());
        statement.setLong(3, nextSequence);
        statement.setString(4, command.recordedAt().toString());
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
      statement.setString(3, command.recordedAt().toString());
      statement.setString(4, command.instance().channel());
      statement.setString(5, command.instance().value());
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

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }
}
