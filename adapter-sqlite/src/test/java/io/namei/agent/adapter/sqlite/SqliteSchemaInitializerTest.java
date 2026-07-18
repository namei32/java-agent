package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
      assertThat(columns(connection, "pending_turn_anchors"))
          .containsExactlyInAnyOrder(
              "operation_ref",
              "session_key",
              "anchor_version",
              "created_next_sequence",
              "resume_next_sequence",
              "state",
              "projection_version");
      assertThat(columns(connection, "pending_turn_anchor_schema"))
          .containsExactlyInAnyOrder("singleton", "version");
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

  @Test
  void initializationIsIdempotentAndPreservesExistingDataAndUnknownColumns() throws Exception {
    Path database = tempDir.resolve("idempotent.db");
    var initializer = new SqliteSchemaInitializer(database, 5_000);
    initializer.initialize();
    try (var connection = initializer.openConnection();
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE sessions ADD COLUMN legacy_note TEXT");
      statement.execute(
          """
          INSERT INTO sessions (key, created_at, updated_at, metadata, legacy_note)
          VALUES ('existing', 'created', 'updated', '{"unknown":true}', 'keep-me')
          """);
    }

    initializer.initialize();

    try (var connection = initializer.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT metadata, legacy_note, next_seq FROM sessions WHERE key = 'existing'")) {
      assertThat(columns(connection, "sessions")).contains("legacy_note");
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("metadata")).isEqualTo("{\"unknown\":true}");
      assertThat(rows.getString("legacy_note")).isEqualTo("keep-me");
      assertThat(rows.getLong("next_seq")).isZero();
    }
  }

  @Test
  void rejectsBrokenMessagesTableWithoutCreatingSessions() throws Exception {
    Path database = tempDir.resolve("broken-messages.db");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE messages (id TEXT PRIMARY KEY)");
    }

    assertThatThrownBy(() -> new SqliteSchemaInitializer(database, 5_000).initialize())
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessageContaining("messages 缺少必需列");

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      assertThat(columns(connection, "messages")).containsExactly("id");
      assertThat(tableNames(connection)).doesNotContain("sessions");
    }
  }

  @Test
  void createsRequiredDefaultsAndUniqueMessageSequenceConstraint() throws Exception {
    var initializer = new SqliteSchemaInitializer(tempDir.resolve("constraints.db"), 5_000);
    initializer.initialize();

    try (var connection = initializer.openConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO sessions (key, created_at, updated_at) VALUES ('session', 'c', 'u')");
      try (var row =
          statement.executeQuery(
              "SELECT last_consolidated, next_seq FROM sessions WHERE key = 'session'")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong("last_consolidated")).isZero();
        assertThat(row.getLong("next_seq")).isZero();
      }
      statement.execute(
          """
          INSERT INTO messages (id, session_key, seq, role, ts)
          VALUES ('first', 'session', 0, 'user', 't')
          """);
      assertThatThrownBy(
              () ->
                  statement.execute(
                      """
                      INSERT INTO messages (id, session_key, seq, role, ts)
                      VALUES ('duplicate-sequence', 'session', 0, 'assistant', 't')
                      """))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("UNIQUE constraint failed");
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO messages (id, session_key, seq, role) VALUES ('missing-ts', 'session', 1, 'user')"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("NOT NULL constraint failed");
    }
  }

  @Test
  void rejectsAnUnknownPendingTurnAnchorSchemaVersion() throws Exception {
    Path database = tempDir.resolve("anchor-version.db");
    var initializer = new SqliteSchemaInitializer(database, 5_000);
    initializer.initialize();
    try (var connection = initializer.openConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE pending_turn_anchor_schema");
      statement.execute(
          "CREATE TABLE pending_turn_anchor_schema (singleton INTEGER PRIMARY KEY, version INTEGER NOT NULL)");
      statement.execute("INSERT INTO pending_turn_anchor_schema(singleton, version) VALUES (1, 2)");
    }

    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessageContaining("Anchor Schema 版本不兼容");
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
        var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
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
