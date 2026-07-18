package io.namei.agent.adapter.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class SqliteSchemaInitializer {
  private static final Set<String> SESSION_COLUMNS =
      Set.of(
          "key",
          "created_at",
          "updated_at",
          "last_consolidated",
          "metadata",
          "last_user_at",
          "last_proactive_at",
          "next_seq");
  private static final Set<String> MESSAGE_COLUMNS =
      Set.of("id", "session_key", "seq", "role", "content", "tool_chain", "extra", "ts");
  private static final Set<String> PENDING_TURN_ANCHOR_COLUMNS =
      Set.of(
          "operation_ref",
          "session_key",
          "anchor_version",
          "created_next_sequence",
          "resume_next_sequence",
          "state",
          "projection_version");
  private static final Set<String> PENDING_TURN_ANCHOR_SCHEMA_COLUMNS =
      Set.of("singleton", "version");

  private static final String CREATE_SESSIONS_SQL =
      """
      CREATE TABLE IF NOT EXISTS sessions (
        key TEXT PRIMARY KEY,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        last_consolidated INTEGER NOT NULL DEFAULT 0,
        metadata TEXT,
        last_user_at TEXT,
        last_proactive_at TEXT,
        next_seq INTEGER NOT NULL DEFAULT 0
      )
      """;
  private static final String CREATE_MESSAGES_SQL =
      """
      CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY,
        session_key TEXT NOT NULL,
        seq INTEGER NOT NULL,
        role TEXT NOT NULL,
        content TEXT,
        tool_chain TEXT,
        extra TEXT,
        ts TEXT NOT NULL,
        UNIQUE(session_key, seq)
      )
      """;
  private static final String CREATE_PENDING_TURN_ANCHORS_SQL =
      """
      CREATE TABLE IF NOT EXISTS pending_turn_anchors (
        operation_ref TEXT PRIMARY KEY NOT NULL,
        session_key TEXT NOT NULL,
        anchor_version INTEGER NOT NULL CHECK (anchor_version = 1),
        created_next_sequence INTEGER NOT NULL CHECK (created_next_sequence >= 0),
        resume_next_sequence INTEGER NOT NULL CHECK (resume_next_sequence = created_next_sequence + 2),
        state TEXT NOT NULL,
        projection_version TEXT NOT NULL
      )
      """;
  private static final String CREATE_PENDING_TURN_ANCHOR_SCHEMA_SQL =
      """
      CREATE TABLE IF NOT EXISTS pending_turn_anchor_schema (
        singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
        version INTEGER NOT NULL CHECK (version = 1)
      )
      """;

  private final String jdbcUrl;
  private final int busyTimeoutMillis;

  public SqliteSchemaInitializer(Path database, int busyTimeoutMillis) {
    this.jdbcUrl = "jdbc:sqlite:" + Objects.requireNonNull(database, "database").toAbsolutePath();
    this.busyTimeoutMillis = busyTimeoutMillis;
  }

  public void initialize() {
    try (var connection = openConnection()) {
      requireColumnsIfTableExists(connection, "sessions", SESSION_COLUMNS);
      requireColumnsIfTableExists(connection, "messages", MESSAGE_COLUMNS);
      requireColumnsIfTableExists(connection, "pending_turn_anchors", PENDING_TURN_ANCHOR_COLUMNS);
      requireColumnsIfTableExists(
          connection, "pending_turn_anchor_schema", PENDING_TURN_ANCHOR_SCHEMA_COLUMNS);

      try (var statement = connection.createStatement()) {
        statement.executeUpdate(CREATE_SESSIONS_SQL);
        statement.executeUpdate(CREATE_MESSAGES_SQL);
        statement.executeUpdate(CREATE_PENDING_TURN_ANCHOR_SCHEMA_SQL);
        statement.executeUpdate(
            "INSERT OR IGNORE INTO pending_turn_anchor_schema(singleton, version) VALUES (1, 1)");
        statement.executeUpdate(CREATE_PENDING_TURN_ANCHORS_SQL);
        statement.executeUpdate(
            "CREATE INDEX IF NOT EXISTS pending_turn_anchors_session_idx "
                + "ON pending_turn_anchors(session_key, resume_next_sequence)");
      }

      requireColumns(connection, "sessions", SESSION_COLUMNS);
      requireColumns(connection, "messages", MESSAGE_COLUMNS);
      requireColumns(connection, "pending_turn_anchors", PENDING_TURN_ANCHOR_COLUMNS);
      requireColumns(connection, "pending_turn_anchor_schema", PENDING_TURN_ANCHOR_SCHEMA_COLUMNS);
      requirePendingTurnAnchorSchemaVersion(connection);
    } catch (SQLException exception) {
      throw new SqliteRepositoryException("Schema 初始化失败: " + exception.getMessage(), exception);
    }
  }

  public Connection openConnection() throws SQLException {
    Connection connection = DriverManager.getConnection(jdbcUrl);
    try {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
      }
      return connection;
    } catch (SQLException exception) {
      try {
        connection.close();
      } catch (SQLException closeException) {
        exception.addSuppressed(closeException);
      }
      throw exception;
    }
  }

  private static void requireColumnsIfTableExists(
      Connection connection, String table, Set<String> required) throws SQLException {
    Set<String> actual = columns(connection, table);
    if (!actual.isEmpty()) {
      requireColumns(table, required, actual);
    }
  }

  private static void requireColumns(Connection connection, String table, Set<String> required)
      throws SQLException {
    requireColumns(table, required, columns(connection, table));
  }

  private static void requireColumns(String table, Set<String> required, Set<String> actual) {
    var missing = new HashSet<>(required);
    missing.removeAll(actual);
    if (!missing.isEmpty()) {
      throw new SqliteRepositoryException(table + " 缺少必需列: " + missing);
    }
  }

  private static Set<String> columns(Connection connection, String table) throws SQLException {
    var actual = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        actual.add(rows.getString("name"));
      }
    }
    return actual;
  }

  private static void requirePendingTurnAnchorSchemaVersion(Connection connection)
      throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT singleton, version FROM pending_turn_anchor_schema")) {
      if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != 1 || rows.next()) {
        throw new SqliteRepositoryException("Pending Turn Anchor Schema 版本不兼容");
      }
    }
  }
}
