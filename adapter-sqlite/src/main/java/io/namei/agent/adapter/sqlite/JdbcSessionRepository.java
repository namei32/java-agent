package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.SessionRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

public final class JdbcSessionRepository implements SessionRepository {
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
            ensureSessionAndReadNext(connection, sessionId, turn.userAt().toString());
        long assistantSequence = Math.addExact(userSequence, 1);
        long followingSequence = Math.addExact(userSequence, 2);
        insertMessage(
            connection,
            sessionId,
            userSequence,
            turn.user(),
            turn.userAt().toString());
        insertMessage(
            connection,
            sessionId,
            assistantSequence,
            turn.assistant(),
            turn.assistantAt().toString());
        updateSession(
            connection,
            sessionId,
            followingSequence,
            turn.userAt().toString(),
            turn.assistantAt().toString());
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollbackPreservingFailure(connection, exception);
        throw exception;
      }
    } catch (SQLException | RuntimeException exception) {
      throw new SqliteRepositoryException("写入完整对话轮次失败", exception);
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

  private static long ensureSessionAndReadNext(
      Connection connection, String sessionId, String now) throws SQLException {
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
      Connection connection,
      String sessionId,
      long sequence,
      ChatMessage message,
      String timestamp)
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

  private static void rollbackPreservingFailure(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      failure.addSuppressed(rollbackException);
    }
  }
}
