package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("failure")
class JdbcSessionRepositoryTest {
  private static final OffsetDateTime USER_AT = OffsetDateTime.parse("2026-07-13T08:00:00+08:00");
  private static final OffsetDateTime ASSISTANT_AT = USER_AT.plusSeconds(1);

  @TempDir Path tempDir;
  private SqliteSchemaInitializer schema;
  private JdbcSessionRepository repository;

  @BeforeEach
  void setUp() {
    schema = new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    schema.initialize();
    repository = new JdbcSessionRepository(schema);
  }

  @Test
  void storesPythonCompatibleTurnAndRestoresItInSequence() throws Exception {
    repository.appendTurn("demo", turn(" 你好 ", " 你好！ "));

    var reopened = new JdbcSessionRepository(schema).load("demo");

    assertThat(reopened.nextSequence()).isEqualTo(2);
    assertThat(reopened.messages())
        .containsExactly(
            new ChatMessage(MessageRole.USER, "你好"), new ChatMessage(MessageRole.ASSISTANT, "你好！"));
    try (var connection = schema.openConnection();
        var session =
            connection
                .createStatement()
                .executeQuery(
                    """
                    SELECT created_at, updated_at, last_consolidated, metadata,
                           last_user_at, last_proactive_at, next_seq
                    FROM sessions WHERE key = 'demo'
                    """)) {
      assertThat(session.next()).isTrue();
      assertThat(session.getString("created_at")).isEqualTo(USER_AT.toString());
      assertThat(session.getString("updated_at")).isEqualTo(ASSISTANT_AT.toString());
      assertThat(session.getInt("last_consolidated")).isZero();
      assertThat(session.getString("metadata")).isEqualTo("{}");
      assertThat(session.getString("last_user_at")).isEqualTo(USER_AT.toString());
      assertThat(session.getObject("last_proactive_at")).isNull();
      assertThat(session.getLong("next_seq")).isEqualTo(2);
    }
    try (var connection = schema.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    """
                    SELECT id, seq, role, content, tool_chain, extra, ts
                    FROM messages ORDER BY seq
                    """)) {
      assertMessage(rows, "demo:0", 0, "user", "你好", USER_AT.toString());
      assertMessage(rows, "demo:1", 1, "assistant", "你好！", ASSISTANT_AT.toString());
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void restoresNextSequenceFromHigherOfSessionAndMessages() throws Exception {
    insertSession("recovered", 1);
    insertMessage("recovered", 4, "user", "较晚问题");
    insertMessage("recovered", 5, "assistant", "较晚回答");

    var fromMessages = repository.load("recovered");

    assertThat(fromMessages.nextSequence()).isEqualTo(6);
    assertThat(fromMessages.messages())
        .containsExactly(
            new ChatMessage(MessageRole.USER, "较晚问题"),
            new ChatMessage(MessageRole.ASSISTANT, "较晚回答"));

    try (var connection = schema.openConnection();
        var update =
            connection.prepareStatement("UPDATE sessions SET next_seq = 9 WHERE key = ?")) {
      update.setString(1, "recovered");
      update.executeUpdate();
    }

    assertThat(repository.load("recovered").nextSequence()).isEqualTo(9);
  }

  @Test
  void restoresAllKnownRolesWithoutChangingTheirOrder() throws Exception {
    insertSession("roles", 3);
    insertMessage("roles", 0, "user", "问题");
    insertMessage("roles", 1, "system", "系统说明");
    insertMessage("roles", 2, "assistant", "回答");

    assertThat(repository.load("roles").messages())
        .containsExactly(
            new ChatMessage(MessageRole.USER, "问题"),
            new ChatMessage(MessageRole.SYSTEM, "系统说明"),
            new ChatMessage(MessageRole.ASSISTANT, "回答"));
  }

  @Test
  void rejectsUnknownRolesInsteadOfSilentlyChangingMessageOrder() throws Exception {
    insertSession("unknown", 2);
    insertMessage("unknown", 0, "user", "问题");
    insertMessage("unknown", 1, "tool", "工具结果");

    assertThatThrownBy(() -> repository.load("unknown"))
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessageContaining("未知消息角色")
        .hasMessageContaining("tool");
  }

  @Test
  void rollsBackWholeTurnWhenAssistantInsertFails() throws Exception {
    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TRIGGER fail_assistant BEFORE INSERT ON messages
          WHEN NEW.role = 'assistant' BEGIN SELECT RAISE(ABORT, 'boom'); END
          """);
    }

    assertThatThrownBy(() -> repository.appendTurn("rollback", turn("问题", "回答")))
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessageContaining("写入完整对话轮次失败");

    assertThat(repository.load("rollback").messages()).isEmpty();
    try (var connection = schema.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery("SELECT COUNT(*) FROM sessions WHERE key = 'rollback'")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isZero();
    }
  }

  @Test
  void rollsBackExistingSessionStateWhenSecondAssistantInsertFails() throws Exception {
    repository.appendTurn("existing", turn("第一问", "第一答"));
    var before = sessionState("existing");
    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TRIGGER fail_second_assistant BEFORE INSERT ON messages
          WHEN NEW.role = 'assistant' AND NEW.seq = 3
          BEGIN SELECT RAISE(ABORT, 'boom'); END
          """);
    }
    var secondUserAt = USER_AT.plusMinutes(1);
    var secondTurn =
        new PersistedTurn(
            new ChatMessage(MessageRole.USER, "第二问"),
            secondUserAt,
            new ChatMessage(MessageRole.ASSISTANT, "第二答"),
            secondUserAt.plusSeconds(1));

    assertThatThrownBy(() -> repository.appendTurn("existing", secondTurn))
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessageContaining("写入完整对话轮次失败");

    assertThat(sessionState("existing")).isEqualTo(before);
    assertThat(repository.load("existing").messages())
        .containsExactly(
            new ChatMessage(MessageRole.USER, "第一问"),
            new ChatMessage(MessageRole.ASSISTANT, "第一答"));
  }

  @Test
  void appendsAfterRecoveredMessageCursorWithoutReusingIds() throws Exception {
    insertSession("recovered-append", 1);
    insertMessage("recovered-append", 4, "user", "较晚问题");
    insertMessage("recovered-append", 5, "assistant", "较晚回答");

    repository.appendTurn("recovered-append", turn("新问题", "新回答"));

    assertThat(repository.load("recovered-append").nextSequence()).isEqualTo(8);
    try (var connection = schema.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT id, seq FROM messages WHERE session_key = 'recovered-append' ORDER BY seq")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("id")).isEqualTo("recovered-append:4");
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("id")).isEqualTo("recovered-append:5");
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("id")).isEqualTo("recovered-append:6");
      assertThat(rows.getLong("seq")).isEqualTo(6);
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("id")).isEqualTo("recovered-append:7");
      assertThat(rows.getLong("seq")).isEqualTo(7);
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void reportsWhetherSQLiteCanBeOpened() {
    assertThat(repository.isAvailable()).isTrue();

    var unavailable =
        new JdbcSessionRepository(
            new SqliteSchemaInitializer(tempDir.resolve("missing/parent/sessions.db"), 5_000));

    assertThat(unavailable.isAvailable()).isFalse();
  }

  private PersistedTurn turn(String user, String assistant) {
    return new PersistedTurn(
        new ChatMessage(MessageRole.USER, user),
        USER_AT,
        new ChatMessage(MessageRole.ASSISTANT, assistant),
        ASSISTANT_AT);
  }

  private void insertSession(String sessionId, long nextSequence) throws Exception {
    try (var connection = schema.openConnection();
        var insert =
            connection.prepareStatement(
                """
                INSERT INTO sessions (
                  key, created_at, updated_at, last_consolidated, metadata,
                  last_user_at, last_proactive_at, next_seq
                ) VALUES (?, ?, ?, 0, '{}', NULL, NULL, ?)
                """)) {
      insert.setString(1, sessionId);
      insert.setString(2, USER_AT.toString());
      insert.setString(3, USER_AT.toString());
      insert.setLong(4, nextSequence);
      insert.executeUpdate();
    }
  }

  private void insertMessage(String sessionId, long sequence, String role, String content)
      throws Exception {
    try (var connection = schema.openConnection();
        var insert =
            connection.prepareStatement(
                """
                INSERT INTO messages (id, session_key, seq, role, content, tool_chain, extra, ts)
                VALUES (?, ?, ?, ?, ?, NULL, '{}', ?)
                """)) {
      insert.setString(1, sessionId + ":" + sequence);
      insert.setString(2, sessionId);
      insert.setLong(3, sequence);
      insert.setString(4, role);
      insert.setString(5, content);
      insert.setString(6, USER_AT.toString());
      insert.executeUpdate();
    }
  }

  private SessionState sessionState(String sessionId) throws Exception {
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT created_at, updated_at, metadata, last_user_at,
                       last_proactive_at, next_seq
                FROM sessions WHERE key = ?
                """)) {
      statement.setString(1, sessionId);
      try (var row = statement.executeQuery()) {
        assertThat(row.next()).isTrue();
        return new SessionState(
            row.getString("created_at"),
            row.getString("updated_at"),
            row.getString("metadata"),
            row.getString("last_user_at"),
            row.getString("last_proactive_at"),
            row.getLong("next_seq"));
      }
    }
  }

  private static void assertMessage(
      java.sql.ResultSet rows,
      String id,
      long sequence,
      String role,
      String content,
      String timestamp)
      throws Exception {
    assertThat(rows.next()).isTrue();
    assertThat(rows.getString("id")).isEqualTo(id);
    assertThat(rows.getLong("seq")).isEqualTo(sequence);
    assertThat(rows.getString("role")).isEqualTo(role);
    assertThat(rows.getString("content")).isEqualTo(content);
    assertThat(rows.getObject("tool_chain")).isNull();
    assertThat(rows.getString("extra")).isEqualTo("{}");
    assertThat(rows.getString("ts")).isEqualTo(timestamp);
  }

  private record SessionState(
      String createdAt,
      String updatedAt,
      String metadata,
      String lastUserAt,
      String lastProactiveAt,
      long nextSequence) {}
}
