package io.namei.agent.adapter.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** 创建并校验有意隔离的 Approval Inbox SQLite 边界。 */
public final class ApprovalInboxSchemaInitializer {
  private static final String DATABASE_FILE_NAME = "approval-inbox.db";
  private static final int VERSION = 2;
  private static final Set<String> V1_TABLES =
      Set.of("approval_inbox_schema", "approval_inbox_entries");
  private static final Set<String> REQUIRED_TABLES =
      Set.of(
          "approval_inbox_schema",
          "approval_inbox_entries",
          "pending_operations",
          "side_effect_reservations");

  private final Path database;
  private final String jdbcUrl;
  private final int busyTimeoutMillis;
  private final ReentrantLock initializationGate = new ReentrantLock();

  public ApprovalInboxSchemaInitializer(Path database, int busyTimeoutMillis) {
    Objects.requireNonNull(database, "database");
    if (database.getFileName() == null
        || !DATABASE_FILE_NAME.equals(database.getFileName().toString())) {
      throw new IllegalArgumentException("审批收件箱数据库文件名必须为 approval-inbox.db");
    }
    if (busyTimeoutMillis < 1 || busyTimeoutMillis > 60_000) {
      throw new IllegalArgumentException("SQLite Busy Timeout 必须在 1..60000");
    }
    this.database = database.toAbsolutePath().normalize();
    this.jdbcUrl = "jdbc:sqlite:" + this.database;
    this.busyTimeoutMillis = busyTimeoutMillis;
  }

  public void initialize() {
    initializationGate.lock();
    try {
      Path parent = database.getParent();
      if (parent == null) {
        throw new IOException("approval inbox database parent missing");
      }
      Files.createDirectories(parent);
      try (var connection = openConnection()) {
        requireHealthyDatabase(connection);
        Set<String> tables = userTables(connection);
        if (tables.isEmpty()) {
          transaction(
              connection,
              () -> {
                createV2Schema(connection);
                validateV2(connection);
                return null;
              });
          return;
        }
        if (tables.equals(V1_TABLES)) {
          transaction(
              connection,
              () -> {
                validateV1(connection);
                migrateV1ToV2(connection);
                validateV2(connection);
                return null;
              });
          return;
        }
        if (!tables.equals(REQUIRED_TABLES)) {
          throw ApprovalInboxRepositoryException.incompatible();
        }
        validateV2(connection);
      }
    } catch (ApprovalInboxRepositoryException exception) {
      throw exception;
    } catch (IOException | SQLException exception) {
      throw ApprovalInboxRepositoryException.unavailable(exception);
    } finally {
      initializationGate.unlock();
    }
  }

  public Connection openConnection() throws SQLException {
    Connection connection = DriverManager.getConnection(jdbcUrl);
    try {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
        statement.execute("PRAGMA foreign_keys=ON");
      }
      return connection;
    } catch (SQLException exception) {
      try {
        connection.close();
      } catch (SQLException closeFailure) {
        exception.addSuppressed(closeFailure);
      }
      throw exception;
    }
  }

  private static void createV2Schema(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE approval_inbox_schema (
            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
            version INTEGER NOT NULL CHECK (version = 2)
          )
          """);
      statement.execute("INSERT INTO approval_inbox_schema(singleton, version) VALUES (1, 2)");
      statement.execute(
          """
          CREATE TABLE approval_inbox_entries (
            approval_id TEXT PRIMARY KEY NOT NULL,
            approval_ref TEXT UNIQUE NOT NULL,
            session_binding TEXT NOT NULL,
            turn_id TEXT NOT NULL,
            call_id TEXT NOT NULL,
            tool_name TEXT NOT NULL,
            tool_version TEXT NOT NULL,
            risk TEXT NOT NULL,
            arguments_hash TEXT NOT NULL,
            idempotency_key TEXT NOT NULL,
            summary TEXT NOT NULL,
            issued_at TEXT NOT NULL,
            issued_epoch_second INTEGER NOT NULL,
            issued_nano INTEGER NOT NULL,
            expires_at TEXT NOT NULL,
            expires_epoch_second INTEGER NOT NULL,
            expires_nano INTEGER NOT NULL,
            fingerprint_version TEXT NOT NULL,
            fingerprint TEXT NOT NULL,
            state TEXT NOT NULL,
            decided_at TEXT,
            actor_reference TEXT NOT NULL
          )
          """);
      statement.execute(
          "CREATE INDEX approval_inbox_entries_list_idx "
              + "ON approval_inbox_entries(issued_epoch_second, issued_nano, approval_ref)");
      statement.execute(
          "CREATE INDEX approval_inbox_entries_expiry_idx "
              + "ON approval_inbox_entries(state, expires_epoch_second, expires_nano)");
      createV2OperationTables(statement);
    }
  }

  private static void migrateV1ToV2(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE approval_inbox_schema RENAME TO approval_inbox_schema_v1");
      statement.execute(
          """
          CREATE TABLE approval_inbox_schema (
            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
            version INTEGER NOT NULL CHECK (version = 2)
          )
          """);
      statement.execute("INSERT INTO approval_inbox_schema(singleton, version) VALUES (1, 2)");
      statement.execute("DROP TABLE approval_inbox_schema_v1");
      createV2OperationTables(statement);
    }
  }

  private static void createV2OperationTables(java.sql.Statement statement) throws SQLException {
    statement.execute(
        """
        CREATE TABLE pending_operations (
          operation_ref TEXT PRIMARY KEY NOT NULL,
          approval_id TEXT UNIQUE NOT NULL REFERENCES approval_inbox_entries(approval_id),
          approval_ref TEXT UNIQUE NOT NULL REFERENCES approval_inbox_entries(approval_ref),
          expected_next_sequence INTEGER NOT NULL CHECK (expected_next_sequence >= 0),
          state TEXT NOT NULL,
          state_changed_at TEXT NOT NULL,
          capsule_schema_version INTEGER NOT NULL CHECK (capsule_schema_version = 1),
          capsule_key_id TEXT NOT NULL,
          capsule_nonce BLOB NOT NULL CHECK (length(capsule_nonce) = 12),
          capsule_ciphertext BLOB NOT NULL CHECK (length(capsule_ciphertext) >= 16)
        )
        """);
    statement.execute(
        "CREATE INDEX pending_operations_state_idx "
            + "ON pending_operations(state, state_changed_at, operation_ref)");
    statement.execute(
        """
        CREATE TABLE side_effect_reservations (
          operation_ref TEXT PRIMARY KEY NOT NULL REFERENCES pending_operations(operation_ref),
          approval_id TEXT UNIQUE NOT NULL REFERENCES approval_inbox_entries(approval_id),
          state TEXT NOT NULL,
          state_changed_at TEXT NOT NULL,
          safe_result TEXT,
          error_code TEXT NOT NULL
        )
        """);
  }

  private static void validateV1(Connection connection) throws SQLException {
    validateVersion(connection, 1);
    validateInboxColumns(connection);
  }

  private static void validateV2(Connection connection) throws SQLException {
    validateVersion(connection, VERSION);
    validateInboxColumns(connection);
    validateColumns(
        connection,
        "pending_operations",
        Set.of(
            "operation_ref",
            "approval_id",
            "approval_ref",
            "expected_next_sequence",
            "state",
            "state_changed_at",
            "capsule_schema_version",
            "capsule_key_id",
            "capsule_nonce",
            "capsule_ciphertext"));
    validateColumns(
        connection,
        "side_effect_reservations",
        Set.of(
            "operation_ref",
            "approval_id",
            "state",
            "state_changed_at",
            "safe_result",
            "error_code"));
  }

  private static void validateVersion(Connection connection, int expectedVersion)
      throws SQLException {
    try (var statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT singleton, version FROM approval_inbox_schema")) {
      if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != expectedVersion || rows.next()) {
        throw ApprovalInboxRepositoryException.incompatible();
      }
    }
  }

  private static void validateInboxColumns(Connection connection) throws SQLException {
    Set<String> required =
        Set.of(
            "approval_id",
            "approval_ref",
            "session_binding",
            "turn_id",
            "call_id",
            "tool_name",
            "tool_version",
            "risk",
            "arguments_hash",
            "idempotency_key",
            "summary",
            "issued_at",
            "issued_epoch_second",
            "issued_nano",
            "expires_at",
            "expires_epoch_second",
            "expires_nano",
            "fingerprint_version",
            "fingerprint",
            "state",
            "decided_at",
            "actor_reference");
    validateColumns(connection, "approval_inbox_entries", required);
  }

  private static void validateColumns(Connection connection, String table, Set<String> required)
      throws SQLException {
    Set<String> columns = new HashSet<>();
    try (var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    if (!columns.equals(required)
        || (table.equals("approval_inbox_entries") && columns.contains("arguments"))) {
      throw ApprovalInboxRepositoryException.incompatible();
    }
  }

  private static Set<String> userTables(Connection connection) throws SQLException {
    Set<String> tables = new HashSet<>();
    try (var statement =
            connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'");
        var rows = statement.executeQuery()) {
      while (rows.next()) {
        tables.add(rows.getString(1));
      }
    }
    return Set.copyOf(tables);
  }

  private static void requireHealthyDatabase(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA quick_check(1)")) {
      if (!rows.next() || !"ok".equalsIgnoreCase(rows.getString(1)) || rows.next()) {
        throw ApprovalInboxRepositoryException.unavailable(null);
      }
    }
  }

  static <T> T transaction(Connection connection, SqlWork<T> work) throws SQLException {
    connection.setAutoCommit(false);
    try {
      T result = work.run();
      connection.commit();
      return result;
    } catch (SQLException | RuntimeException exception) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        exception.addSuppressed(rollbackFailure);
      }
      throw exception;
    } finally {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException ignored) {
        // 连接会立即关闭，保留主要失败。
      }
    }
  }

  /**
   * 在读取必然发生变更的状态机之前启动 SQLite 写事务，防止两个延迟读取者同时尝试升级 Approval 消费状态，将正常的单获胜者竞争 变成 {@code SQLITE_BUSY}。
   */
  static <T> T immediateTransaction(Connection connection, SqlWork<T> work) throws SQLException {
    executeTransactionControl(connection, "BEGIN IMMEDIATE");
    try {
      T result = work.run();
      executeTransactionControl(connection, "COMMIT");
      return result;
    } catch (SQLException | RuntimeException exception) {
      rollbackImmediatePreservingFailure(connection, exception);
      throw exception;
    }
  }

  private static void executeTransactionControl(Connection connection, String command)
      throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(command);
    }
  }

  private static void rollbackImmediatePreservingFailure(Connection connection, Exception failure) {
    try {
      executeTransactionControl(connection, "ROLLBACK");
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  @FunctionalInterface
  interface SqlWork<T> {
    T run() throws SQLException;
  }
}
