package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("compat")
class PythonSchemaCompatibilityIT {
  @TempDir Path tempDir;

  @Test
  void readsPythonRowsAndPreservesUnknownDataWhenAppending() throws Exception {
    Path database = tempDir.resolve("sessions.db");
    var schema = new SqliteSchemaInitializer(database, 5_000);
    String sql = readSchema();
    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      for (String command : sql.split(";")) {
        if (!command.isBlank()) {
          statement.execute(command);
        }
      }
    }
    schema.initialize();
    var repository = new JdbcSessionRepository(schema);

    assertThat(repository.load("python-demo").messages())
        .containsExactly(
            new ChatMessage(MessageRole.USER, "Python 问题"),
            new ChatMessage(MessageRole.ASSISTANT, "Python 回答"));

    OffsetDateTime now = OffsetDateTime.parse("2026-07-13T08:00:00+08:00");
    repository.appendTurn(
        "python-demo",
        new PersistedTurn(
            new ChatMessage(MessageRole.USER, "Java 问题"),
            now,
            new ChatMessage(MessageRole.ASSISTANT, "Java 回答"),
            now.plusSeconds(1)));

    try (var connection = schema.openConnection();
        var session =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT metadata, future_column FROM sessions WHERE key='python-demo'")) {
      assertThat(session.next()).isTrue();
      assertThat(session.getString("metadata")).isEqualTo("{\"unknown\":\"keep\"}");
      assertThat(session.getString("future_column")).isEqualTo("keep-session");
    }
    try (var connection = schema.openConnection();
        var messages =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT future_column FROM messages WHERE session_key='python-demo' AND seq < 2 ORDER BY seq")) {
      assertThat(messages.next()).isTrue();
      assertThat(messages.getString(1)).isEqualTo("keep-message-0");
      assertThat(messages.next()).isTrue();
      assertThat(messages.getString(1)).isEqualTo("keep-message-1");
    }
  }

  private String readSchema() throws IOException {
    try (var input = getClass().getResourceAsStream("/python-session-schema.sql")) {
      if (input == null) {
        throw new IOException("缺少 Python Schema 测试资源");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
