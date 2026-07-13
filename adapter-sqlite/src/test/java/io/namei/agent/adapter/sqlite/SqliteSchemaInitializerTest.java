package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("failure")
class SqliteSchemaInitializerTest {
  @TempDir Path tempDir;

  @Test
  void createsPythonCompatibleCoreTables() throws Exception {
    var initializer = new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);

    initializer.initialize();

    try (var connection = initializer.openConnection()) {
      assertThat(columns(connection, "sessions"))
          .containsExactlyInAnyOrder(
              "key",
              "created_at",
              "updated_at",
              "last_consolidated",
              "metadata",
              "last_user_at",
              "last_proactive_at",
              "next_seq");
      assertThat(columns(connection, "messages"))
          .containsExactlyInAnyOrder(
              "id", "session_key", "seq", "role", "content", "tool_chain", "extra", "ts");
      assertThat(pragmaInt(connection, "busy_timeout")).isEqualTo(5_000);
    }
  }

  @Test
  void rejectsAnExistingTableMissingRequiredColumnsWithoutMutatingIt() throws Exception {
    Path database = tempDir.resolve("broken.db");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE sessions (key TEXT PRIMARY KEY)");
    }

    assertThatThrownBy(() -> new SqliteSchemaInitializer(database, 5_000).initialize())
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessageContaining("sessions 缺少必需列");

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      assertThat(columns(connection, "sessions")).containsExactly("key");
      assertThat(tableNames(connection)).doesNotContain("messages");
    }
  }

  private static Set<String> columns(Connection connection, String table) throws Exception {
    var names = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
    }
    return names;
  }

  private static Set<String> tableNames(Connection connection) throws Exception {
    var names = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
    }
    return names;
  }

  private static int pragmaInt(Connection connection, String name) throws Exception {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA " + name)) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }
}
