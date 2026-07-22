package io.namei.agent.adapter.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** 自持版本化本地 Scheduler Schema；在 initialize() 完成前，任何调用方都拿不到连接。 */
public final class ProactiveSchemaInitializer {
  private static final String DATABASE_FILE_NAME = "proactive-runtime.db";
  private static final int VERSION = 1;
  private static final Set<String> TABLES = Set.of("proactive_schema", "proactive_jobs");

  private final Path database;
  private final String jdbcUrl;
  private final int busyTimeoutMillis;
  private final ReentrantLock initializationGate = new ReentrantLock();

  public ProactiveSchemaInitializer(Path database, int busyTimeoutMillis) {
    Objects.requireNonNull(database, "database");
    if (database.getFileName() == null
        || !DATABASE_FILE_NAME.equals(database.getFileName().toString())) {
      throw new IllegalArgumentException("Proactive 数据库文件名必须为 proactive-runtime.db");
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
        throw new IOException("database parent missing");
      }
      Files.createDirectories(parent);
      try (var connection = openConnection()) {
        Set<String> tables = userTables(connection);
        if (tables.isEmpty()) {
          transaction(connection, () -> createV1(connection));
          return;
        }
        if (!tables.equals(TABLES) || readVersion(connection) != VERSION) {
          throw ProactiveRepositoryException.schemaIncompatible();
        }
      }
    } catch (ProactiveRepositoryException exception) {
      throw exception;
    } catch (IOException | SQLException exception) {
      throw ProactiveRepositoryException.unavailable(exception);
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

  private static void createV1(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE proactive_schema (
            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
            version INTEGER NOT NULL,
            updated_at TEXT NOT NULL
          )
          """);
      statement.execute(
          """
          CREATE TABLE proactive_jobs (
            job_ref TEXT PRIMARY KEY,
            schedule_kind TEXT NOT NULL,
            next_run_at TEXT NOT NULL,
            every_millis INTEGER,
            target_hash TEXT NOT NULL,
            idempotency_key TEXT NOT NULL UNIQUE,
            state TEXT NOT NULL,
            attempts INTEGER NOT NULL,
            max_attempts INTEGER NOT NULL,
            owner_id TEXT,
            lease_expires_at TEXT,
            revision INTEGER NOT NULL,
            updated_at TEXT NOT NULL,
            CHECK (schedule_kind IN ('AT', 'EVERY')),
            CHECK ((schedule_kind = 'AT' AND every_millis IS NULL) OR
                   (schedule_kind = 'EVERY' AND every_millis >= 1000)),
            CHECK (state IN ('SCHEDULED', 'CLAIMED', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED', 'CANCELLED')),
            CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 8),
            CHECK ((state IN ('CLAIMED', 'RUNNING')) = (owner_id IS NOT NULL AND lease_expires_at IS NOT NULL))
          )
          """);
      statement.execute(
          "CREATE INDEX ix_proactive_jobs_due ON proactive_jobs(state, next_run_at, job_ref)");
      statement.executeUpdate(
          "INSERT INTO proactive_schema(singleton, version, updated_at) VALUES (1, 1, '"
              + Instant.EPOCH
              + "')");
    }
  }

  private static Set<String> userTables(Connection connection) throws SQLException {
    var tables = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
      while (rows.next()) {
        tables.add(rows.getString(1));
      }
    }
    return Set.copyOf(tables);
  }

  private static int readVersion(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT version FROM proactive_schema WHERE singleton = 1")) {
      if (!rows.next() || rows.getInt(1) != VERSION || rows.next()) {
        throw ProactiveRepositoryException.schemaIncompatible();
      }
      return VERSION;
    }
  }

  private static void transaction(Connection connection, SqlWork work) throws SQLException {
    connection.setAutoCommit(false);
    try {
      work.run();
      connection.commit();
    } catch (SQLException | RuntimeException exception) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        exception.addSuppressed(rollbackFailure);
      }
      throw exception;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run() throws SQLException;
  }
}
