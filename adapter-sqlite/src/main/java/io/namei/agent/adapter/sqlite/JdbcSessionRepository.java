package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnAnchorState;
import io.namei.agent.kernel.model.PendingTurnResolution;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.SessionRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class JdbcSessionRepository implements SessionRepository {
  private static final DateTimeFormatter STORAGE_TIMESTAMP =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
          .optionalEnd()
          .appendOffsetId()
          .toFormatter();

  private final SqliteSchemaInitializer schema;

  public JdbcSessionRepository(SqliteSchemaInitializer schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  @Override
  public SessionSnapshot load(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    try (var connection = schema.openConnection()) {
      long nextSequence = readNextSequence(connection, sessionId);
      var messages = new ArrayList<ChatMessage>();
      try (var statement =
          connection.prepareStatement(
              "SELECT role, content FROM messages WHERE session_key = ? ORDER BY seq")) {
        statement.setString(1, sessionId);
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            messages.add(readMessage(rows));
          }
        }
      }
      return new SessionSnapshot(sessionId, messages, nextSequence);
    } catch (SQLException exception) {
      throw new SqliteRepositoryException("读取 Session 失败", exception);
    }
  }

  @Override
  public void appendTurn(String sessionId, PersistedTurn turn) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(turn, "turn");
    try (var connection = schema.openConnection()) {
      connection.setAutoCommit(false);
      try {
        long userSequence =
            ensureSessionAndReadNext(connection, sessionId, timestamp(turn.userAt()));
        long assistantSequence = Math.addExact(userSequence, 1);
        long followingSequence = Math.addExact(userSequence, 2);
        insertMessage(connection, sessionId, userSequence, turn.user(), timestamp(turn.userAt()));
        insertMessage(
            connection,
            sessionId,
            assistantSequence,
            turn.assistant(),
            timestamp(turn.assistantAt()));
        updateSession(
            connection,
            sessionId,
            followingSequence,
            timestamp(turn.userAt()),
            timestamp(turn.assistantAt()));
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollbackPreservingFailure(connection, exception);
        throw exception;
      }
    } catch (SQLException | RuntimeException exception) {
      throw new SqliteRepositoryException("写入完整对话轮次失败", exception);
    }
  }

  @Override
  public boolean appendTurnIfNextSequence(
      String sessionId, long expectedNextSequence, PersistedTurn turn) {
    Objects.requireNonNull(sessionId, "sessionId");
    if (expectedNextSequence < 0) {
      throw new IllegalArgumentException("预期 Session 序号不能为负数");
    }
    Objects.requireNonNull(turn, "turn");
    try (var connection = schema.openConnection()) {
      connection.setAutoCommit(false);
      try {
        long actualSequence =
            ensureSessionAndReadNext(connection, sessionId, timestamp(turn.userAt()));
        if (actualSequence != expectedNextSequence
            || !advanceSessionIfExpected(
                connection,
                sessionId,
                expectedNextSequence,
                Math.addExact(expectedNextSequence, 2),
                timestamp(turn.userAt()),
                timestamp(turn.assistantAt()))) {
          connection.rollback();
          return false;
        }
        insertMessage(
            connection, sessionId, expectedNextSequence, turn.user(), timestamp(turn.userAt()));
        insertMessage(
            connection,
            sessionId,
            Math.addExact(expectedNextSequence, 1),
            turn.assistant(),
            timestamp(turn.assistantAt()));
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException exception) {
        rollbackPreservingFailure(connection, exception);
        throw exception;
      }
    } catch (SQLException | RuntimeException exception) {
      throw new SqliteRepositoryException("条件写入完整对话轮次失败", exception);
    }
  }

  @Override
  public boolean appendPendingTurnIfNextSequence(
      PersistedTurn pendingTurn, PendingTurnAnchor anchor) {
    Objects.requireNonNull(pendingTurn, "pendingTurn");
    Objects.requireNonNull(anchor, "anchor");
    if (anchor.state() != PendingTurnAnchorState.PENDING_APPROVAL) {
      throw new IllegalArgumentException("只能写入待审批 Pending Turn Anchor");
    }
    try (var connection = schema.openConnection()) {
      connection.setAutoCommit(false);
      try {
        long actualSequence =
            ensureSessionAndReadNext(
                connection, anchor.sessionId(), timestamp(pendingTurn.userAt()));
        if (actualSequence != anchor.createdNextSequence()
            || !advanceSessionIfExpected(
                connection,
                anchor.sessionId(),
                anchor.createdNextSequence(),
                anchor.resumeNextSequence(),
                timestamp(pendingTurn.userAt()),
                timestamp(pendingTurn.assistantAt()))) {
          connection.rollback();
          return false;
        }
        insertMessage(
            connection,
            anchor.sessionId(),
            anchor.createdNextSequence(),
            pendingTurn.user(),
            timestamp(pendingTurn.userAt()));
        insertMessage(
            connection,
            anchor.sessionId(),
            Math.addExact(anchor.createdNextSequence(), 1),
            pendingTurn.assistant(),
            timestamp(pendingTurn.assistantAt()));
        insertPendingTurnAnchor(connection, anchor);
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException exception) {
        rollbackPreservingFailure(connection, exception);
        throw exception;
      }
    } catch (SQLException | RuntimeException exception) {
      throw new SqliteRepositoryException("写入 Pending Turn 与 Anchor 失败", exception);
    }
  }

  @Override
  public Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
    Objects.requireNonNull(operationReference, "operationReference");
    try (var connection = schema.openConnection()) {
      return readPendingTurnAnchor(connection, operationReference);
    } catch (SQLException | RuntimeException exception) {
      throw new SqliteRepositoryException("读取 Pending Turn Anchor 失败", exception);
    }
  }

  @Override
  public boolean cancelPendingTurnAnchorIfMatches(PendingTurnAnchor anchor) {
    Objects.requireNonNull(anchor, "anchor");
    if (anchor.state() != PendingTurnAnchorState.PENDING_APPROVAL) {
      return false;
    }
    try (var connection = schema.openConnection()) {
      connection.setAutoCommit(false);
      try {
        if (readPendingTurnAnchor(connection, anchor.operationReference())
            .filter(anchor::equals)
            .isEmpty()) {
          connection.rollback();
          return false;
        }
        transitionPendingTurnAnchor(connection, anchor, PendingTurnAnchorState.CANCELLED);
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException exception) {
        rollbackPreservingFailure(connection, exception);
        throw exception;
      }
    } catch (SQLException | RuntimeException exception) {
      throw new SqliteRepositoryException("取消 Pending Turn Anchor 失败", exception);
    }
  }

  @Override
  public boolean appendPendingResolutionIfAnchorMatches(
      PendingTurnAnchor anchor, PendingTurnResolution resolution) {
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(resolution, "resolution");
    if (!anchor.projectionVersion().equals(resolution.projectionVersion())) {
      throw new IllegalArgumentException("Pending Turn Resolution 投影版本与 Anchor 不匹配");
    }
    try (var connection = schema.openConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<PendingTurnAnchor> stored =
            readPendingTurnAnchor(connection, anchor.operationReference());
        if (stored.isEmpty()
            || !stored.orElseThrow().equals(anchor)
            || anchor.state() != PendingTurnAnchorState.PENDING_APPROVAL) {
          connection.rollback();
          return false;
        }

        if (readNextSequence(connection, anchor.sessionId()) != anchor.resumeNextSequence()
            || !advanceSessionForPendingResolution(
                connection,
                anchor.sessionId(),
                anchor.resumeNextSequence(),
                Math.addExact(anchor.resumeNextSequence(), 1),
                timestamp(resolution.resolvedAt()))) {
          transitionPendingTurnAnchor(connection, anchor, PendingTurnAnchorState.STALE_SESSION);
          connection.commit();
          return false;
        }
        insertMessage(
            connection,
            anchor.sessionId(),
            anchor.resumeNextSequence(),
            resolution.safeAssistantProjection(),
            timestamp(resolution.resolvedAt()));
        transitionPendingTurnAnchor(connection, anchor, PendingTurnAnchorState.COMMITTED);
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException exception) {
        rollbackPreservingFailure(connection, exception);
        throw exception;
      }
    } catch (SQLException | RuntimeException exception) {
      throw new SqliteRepositoryException("条件写入 Pending Turn Resolution 失败", exception);
    }
  }

  public boolean isAvailable() {
    try (var connection = schema.openConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT 1")) {
      return rows.next() && rows.getInt(1) == 1;
    } catch (SQLException exception) {
      return false;
    }
  }

  private static ChatMessage readMessage(ResultSet rows) throws SQLException {
    String storedRole = rows.getString("role");
    MessageRole role =
        switch (storedRole) {
          case "system" -> MessageRole.SYSTEM;
          case "user" -> MessageRole.USER;
          case "assistant" -> MessageRole.ASSISTANT;
          default -> throw new SqliteRepositoryException("未知消息角色: " + storedRole);
        };
    String content = rows.getString("content");
    if (content == null) {
      throw new SqliteRepositoryException("消息内容为 NULL，角色: " + storedRole);
    }
    return new ChatMessage(role, content);
  }

  private static long readNextSequence(Connection connection, String sessionId)
      throws SQLException {
    long fromSession = 0;
    try (var statement =
        connection.prepareStatement("SELECT next_seq FROM sessions WHERE key = ?")) {
      statement.setString(1, sessionId);
      try (var row = statement.executeQuery()) {
        if (row.next()) {
          fromSession = Math.max(0, row.getLong(1));
        }
      }
    }

    long fromMessages = 0;
    try (var statement =
        connection.prepareStatement("SELECT MAX(seq) FROM messages WHERE session_key = ?")) {
      statement.setString(1, sessionId);
      try (var row = statement.executeQuery()) {
        if (row.next() && row.getObject(1) != null) {
          long maximumSequence = row.getLong(1);
          try {
            fromMessages = Math.max(0, Math.addExact(maximumSequence, 1));
          } catch (ArithmeticException exception) {
            throw new SqliteRepositoryException("消息序号已耗尽: " + sessionId, exception);
          }
        }
      }
    }
    return Math.max(fromSession, fromMessages);
  }

  private static long ensureSessionAndReadNext(Connection connection, String sessionId, String now)
      throws SQLException {
    try (var insert =
        connection.prepareStatement(
            """
            INSERT INTO sessions (
              key, created_at, updated_at, last_consolidated, metadata,
              last_user_at, last_proactive_at, next_seq
            ) VALUES (?, ?, ?, 0, '{}', NULL, NULL, 0)
            ON CONFLICT(key) DO NOTHING
            """)) {
      insert.setString(1, sessionId);
      insert.setString(2, now);
      insert.setString(3, now);
      insert.executeUpdate();
    }
    return readNextSequence(connection, sessionId);
  }

  private static void insertMessage(
      Connection connection, String sessionId, long sequence, ChatMessage message, String timestamp)
      throws SQLException {
    try (var insert =
        connection.prepareStatement(
            """
            INSERT INTO messages (id, session_key, seq, role, content, tool_chain, extra, ts)
            VALUES (?, ?, ?, ?, ?, NULL, '{}', ?)
            """)) {
      insert.setString(1, sessionId + ":" + sequence);
      insert.setString(2, sessionId);
      insert.setLong(3, sequence);
      insert.setString(4, message.role().name().toLowerCase(Locale.ROOT));
      insert.setString(5, message.content());
      insert.setString(6, timestamp);
      insert.executeUpdate();
    }
  }

  private static void updateSession(
      Connection connection,
      String sessionId,
      long nextSequence,
      String userTimestamp,
      String assistantTimestamp)
      throws SQLException {
    try (var update =
        connection.prepareStatement(
            """
            UPDATE sessions
            SET next_seq = ?, updated_at = ?, last_user_at = ?
            WHERE key = ?
            """)) {
      update.setLong(1, nextSequence);
      update.setString(2, assistantTimestamp);
      update.setString(3, userTimestamp);
      update.setString(4, sessionId);
      if (update.executeUpdate() != 1) {
        throw new SQLException("Session 更新行数不是 1: " + sessionId);
      }
    }
  }

  private static boolean advanceSessionIfExpected(
      Connection connection,
      String sessionId,
      long expectedNextSequence,
      long followingSequence,
      String userTimestamp,
      String assistantTimestamp)
      throws SQLException {
    try (var update =
        connection.prepareStatement(
            """
            UPDATE sessions
            SET next_seq = ?, updated_at = ?, last_user_at = ?
            WHERE key = ? AND next_seq = ?
            """)) {
      update.setLong(1, followingSequence);
      update.setString(2, assistantTimestamp);
      update.setString(3, userTimestamp);
      update.setString(4, sessionId);
      update.setLong(5, expectedNextSequence);
      int updated = update.executeUpdate();
      if (updated < 0 || updated > 1) {
        throw new SQLException("Session 条件更新行数无效: " + sessionId);
      }
      return updated == 1;
    }
  }

  private static boolean advanceSessionForPendingResolution(
      Connection connection,
      String sessionId,
      long expectedNextSequence,
      long followingSequence,
      String resolutionTimestamp)
      throws SQLException {
    try (var update =
        connection.prepareStatement(
            """
            UPDATE sessions
            SET next_seq = ?, updated_at = ?
            WHERE key = ? AND next_seq = ?
            """)) {
      update.setLong(1, followingSequence);
      update.setString(2, resolutionTimestamp);
      update.setString(3, sessionId);
      update.setLong(4, expectedNextSequence);
      int updated = update.executeUpdate();
      if (updated < 0 || updated > 1) {
        throw new SQLException("Session Pending Resolution 条件更新行数无效: " + sessionId);
      }
      return updated == 1;
    }
  }

  private static void insertPendingTurnAnchor(Connection connection, PendingTurnAnchor anchor)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO pending_turn_anchors (
              operation_ref, session_key, anchor_version, created_next_sequence,
              resume_next_sequence, state, projection_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, anchor.operationReference());
      statement.setString(2, anchor.sessionId());
      statement.setInt(3, anchor.anchorVersion());
      statement.setLong(4, anchor.createdNextSequence());
      statement.setLong(5, anchor.resumeNextSequence());
      statement.setString(6, anchor.state().name());
      statement.setString(7, anchor.projectionVersion());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Pending Turn Anchor 写入行数不是 1");
      }
    }
  }

  private static Optional<PendingTurnAnchor> readPendingTurnAnchor(
      Connection connection, String operationReference) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT anchor_version, operation_ref, session_key, created_next_sequence,
                   resume_next_sequence, state, projection_version
            FROM pending_turn_anchors
            WHERE operation_ref = ?
            """)) {
      statement.setString(1, operationReference);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        PendingTurnAnchor anchor =
            new PendingTurnAnchor(
                rows.getInt("anchor_version"),
                rows.getString("operation_ref"),
                rows.getString("session_key"),
                rows.getLong("created_next_sequence"),
                rows.getLong("resume_next_sequence"),
                PendingTurnAnchorState.valueOf(rows.getString("state")),
                rows.getString("projection_version"));
        if (rows.next()) {
          throw new SqliteRepositoryException("Pending Turn Anchor 不是唯一记录");
        }
        return Optional.of(anchor);
      }
    }
  }

  private static void transitionPendingTurnAnchor(
      Connection connection, PendingTurnAnchor anchor, PendingTurnAnchorState next)
      throws SQLException {
    if (!next.isTerminal()) {
      throw new IllegalArgumentException("Pending Turn Anchor 只能转换至终态");
    }
    try (var update =
        connection.prepareStatement(
            """
            UPDATE pending_turn_anchors
            SET state = ?
            WHERE operation_ref = ?
              AND session_key = ?
              AND anchor_version = ?
              AND created_next_sequence = ?
              AND resume_next_sequence = ?
              AND state = 'PENDING_APPROVAL'
              AND projection_version = ?
            """)) {
      update.setString(1, next.name());
      update.setString(2, anchor.operationReference());
      update.setString(3, anchor.sessionId());
      update.setInt(4, anchor.anchorVersion());
      update.setLong(5, anchor.createdNextSequence());
      update.setLong(6, anchor.resumeNextSequence());
      update.setString(7, anchor.projectionVersion());
      if (update.executeUpdate() != 1) {
        throw new SQLException("Pending Turn Anchor 条件状态更新行数不是 1");
      }
    }
  }

  private static void rollbackPreservingFailure(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      failure.addSuppressed(rollbackException);
    }
  }

  private static String timestamp(OffsetDateTime value) {
    return STORAGE_TIMESTAMP.format(value);
  }
}
