package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class PythonSessionStoreGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void readsPythonRowsAndKeepsTheApprovedCoreSchemaAndCursor() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("sqlite/session-store.json"));

    for (JsonNode testCase : fixture.path("cases")) {
      Path database = tempDir.resolve(testCase.path("id").asString() + ".db");
      var schema = new SqliteSchemaInitializer(database, 1_000);
      schema.initialize();
      seed(schema, testCase.path("expectedInitial"));

      assertThat(state(schema)).isEqualTo(testCase.path("expectedInitial"));

      var repository = new JdbcSessionRepository(schema);
      var snapshot = repository.load("python-demo");
      assertThat(snapshot.nextSequence()).isEqualTo(2);
      assertThat(snapshot.messages())
          .containsExactly(
              new ChatMessage(MessageRole.USER, "Python 问题"),
              new ChatMessage(MessageRole.ASSISTANT, "Python 回答"));

      JsonNode append = testCase.path("javaAppend");
      repository.appendTurn(
          "python-demo",
          new PersistedTurn(
              new ChatMessage(MessageRole.USER, append.path("user").asString()),
              java.time.OffsetDateTime.parse(append.path("userAt").asString()),
              new ChatMessage(MessageRole.ASSISTANT, append.path("assistant").asString()),
              java.time.OffsetDateTime.parse(append.path("assistantAt").asString())));

      assertThat(state(schema)).isEqualTo(testCase.path("expectedAfterAppend"));
    }
  }

  private static void seed(SqliteSchemaInitializer schema, JsonNode expected) throws Exception {
    try (Connection connection = schema.openConnection()) {
      for (JsonNode session : expected.path("sessions")) {
        try (var statement =
            connection.prepareStatement(
                """
                INSERT INTO sessions (
                  key, created_at, updated_at, last_consolidated, metadata,
                  last_user_at, last_proactive_at, next_seq
                ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)
                """)) {
          statement.setString(1, session.path("key").asString());
          statement.setString(2, session.path("created_at").asString());
          statement.setString(3, session.path("updated_at").asString());
          statement.setInt(4, session.path("last_consolidated").asInt());
          statement.setString(5, session.path("metadata").asString());
          statement.setInt(6, session.path("next_seq").asInt());
          statement.executeUpdate();
        }
      }
      for (JsonNode message : expected.path("messages")) {
        try (var statement =
            connection.prepareStatement(
                """
                INSERT INTO messages (
                  id, session_key, seq, role, content, tool_chain, extra, ts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
          statement.setString(1, message.path("id").asString());
          statement.setString(2, message.path("session_key").asString());
          statement.setInt(3, message.path("seq").asInt());
          statement.setString(4, message.path("role").asString());
          statement.setString(5, message.path("content").asString());
          statement.setObject(6, nullableText(message, "tool_chain"));
          statement.setString(7, message.path("extra").asString());
          statement.setString(8, message.path("ts").asString());
          statement.executeUpdate();
        }
      }
    }
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() ? null : value.asString();
  }

  private static JsonNode state(SqliteSchemaInitializer schema) throws Exception {
    try (Connection connection = schema.openConnection()) {
      Map<String, Object> state = new LinkedHashMap<>();
      state.put(
          "messages",
          rows(
              connection,
              "SELECT id, session_key, seq, role, content, tool_chain, extra, ts FROM messages ORDER BY session_key, seq",
              List.of("id", "session_key", "seq", "role", "content", "tool_chain", "extra", "ts")));
      Map<String, Object> schemaState = new LinkedHashMap<>();
      schemaState.put("messages", columns(connection, "messages"));
      schemaState.put("sessions", columns(connection, "sessions"));
      schemaState.put("uniqueMessageKeys", uniqueMessageKeys(connection));
      state.put("schema", schemaState);
      state.put(
          "sessions",
          rows(
              connection,
              "SELECT key, created_at, updated_at, last_consolidated, metadata, next_seq FROM sessions ORDER BY key",
              List.of(
                  "key", "created_at", "updated_at", "last_consolidated", "metadata", "next_seq")));
      return JSON.valueToTree(state);
    }
  }

  private static List<Map<String, Object>> rows(
      Connection connection, String sql, List<String> columns) throws Exception {
    var result = new ArrayList<Map<String, Object>>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : columns) {
          Object value = rows.getObject(column);
          row.put(column, value instanceof Number number ? number.intValue() : value);
        }
        result.add(row);
      }
    }
    return result;
  }

  private static List<Map<String, Object>> columns(Connection connection, String table)
      throws Exception {
    var result = new ArrayList<Map<String, Object>>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("defaultValue", rows.getObject("dflt_value"));
        column.put("name", rows.getString("name"));
        column.put("notNull", rows.getBoolean("notnull"));
        column.put("primaryKey", rows.getBoolean("pk"));
        column.put("type", rows.getString("type"));
        result.add(column);
      }
    }
    return result;
  }

  private static List<List<String>> uniqueMessageKeys(Connection connection) throws Exception {
    var result = new ArrayList<List<String>>();
    try (var statement = connection.createStatement();
        var indexes = statement.executeQuery("PRAGMA index_list(messages)")) {
      while (indexes.next()) {
        if (!indexes.getBoolean("unique") || !"u".equals(indexes.getString("origin"))) {
          continue;
        }
        String name = indexes.getString("name").replace("'", "''");
        var columns = new ArrayList<String>();
        try (var indexStatement = connection.createStatement();
            ResultSet rows = indexStatement.executeQuery("PRAGMA index_info('" + name + "')")) {
          while (rows.next()) {
            columns.add(rows.getString("name"));
          }
        }
        result.add(List.copyOf(columns));
      }
    }
    result.sort(Comparator.comparing(columns -> String.join("\u0000", columns)));
    return result;
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
