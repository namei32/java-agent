package io.namei.agent.adapter.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;

public final class JavaMemorySchemaInitializer {
  private static final String DATABASE_FILE_NAME = "agent-memory.db";

  private final Path database;
  private final String jdbcUrl;
  private final int busyTimeoutMillis;
  private final Clock clock;
  private final JavaMemoryBackup backup;
  private final ReentrantLock initializationGate = new ReentrantLock();

  public JavaMemorySchemaInitializer(Path database, int busyTimeoutMillis) {
    this(database, busyTimeoutMillis, Clock.systemUTC(), JavaMemorySchemaInitializer::sqliteBackup);
  }

  JavaMemorySchemaInitializer(
      Path database, int busyTimeoutMillis, Clock clock, JavaMemoryBackup backup) {
    Objects.requireNonNull(database, "database");
    if (database.getFileName() == null
        || !DATABASE_FILE_NAME.equals(database.getFileName().toString())) {
      throw new IllegalArgumentException("Java Memory 数据库文件名必须为 agent-memory.db");
    }
    if (busyTimeoutMillis < 1 || busyTimeoutMillis > 60_000) {
      throw new IllegalArgumentException("SQLite Busy Timeout 必须在 1..60000");
    }
    this.database = database.toAbsolutePath().normalize();
    this.jdbcUrl = "jdbc:sqlite:" + this.database;
    this.busyTimeoutMillis = busyTimeoutMillis;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.backup = Objects.requireNonNull(backup, "backup");
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
        requireHealthyDatabase(connection);
        var tables = JavaMemorySchemaV1.userTables(connection);
        if (tables.isEmpty()) {
          transaction(
              connection,
              () -> {
                JavaMemorySchemaV2.createNew(connection, clock.instant());
                JavaMemorySchemaV2.validateV2(connection);
              });
          return;
        }
        if (!tables.contains("memory_schema")) {
          throw JavaMemoryRepositoryException.schemaIncompatible();
        }
        JavaMemorySchemaV1.validateHeader(connection);
        JavaMemorySchemaV1.SchemaRecord schema = JavaMemorySchemaV1.readSchemaRecord(connection);
        if (schema.version() < 0 || schema.version() > JavaMemorySchemaV2.VERSION) {
          throw JavaMemoryRepositoryException.schemaIncompatible();
        }
        if (schema.version() == JavaMemorySchemaV2.VERSION) {
          JavaMemorySchemaV2.validateV2(connection);
          return;
        }
        if (schema.version() == JavaMemorySchemaV1.VERSION) {
          JavaMemorySchemaV1.validateV1(connection);
          backupVersion(connection, JavaMemorySchemaV1.VERSION, JavaMemorySchemaV2.VERSION);
          transaction(
              connection,
              () -> {
                JavaMemorySchemaV2.migrateV1(connection, clock.instant());
                JavaMemorySchemaV2.validateV2(connection);
              });
          return;
        }

        JavaMemorySchemaV1.validateV0(connection);
        backupVersion(connection, 0, JavaMemorySchemaV1.VERSION);
        transaction(
            connection,
            () -> {
              JavaMemorySchemaV1.migrateV0(connection, clock.instant());
              JavaMemorySchemaV1.validateV1(connection);
            });
        backupVersion(connection, JavaMemorySchemaV1.VERSION, JavaMemorySchemaV2.VERSION);
        transaction(
            connection,
            () -> {
              JavaMemorySchemaV2.migrateV1(connection, clock.instant());
              JavaMemorySchemaV2.validateV2(connection);
            });
      }
    } catch (JavaMemoryRepositoryException exception) {
      throw exception;
    } catch (IOException | SQLException exception) {
      throw JavaMemoryRepositoryException.databaseUnavailable(exception);
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
      } catch (SQLException closeException) {
        exception.addSuppressed(closeException);
      }
      throw exception;
    }
  }

  private void backupVersion(Connection connection, int fromVersion, int toVersion) {
    Path destination =
        database.resolveSibling(
            DATABASE_FILE_NAME
                + ".v"
                + fromVersion
                + "-to-v"
                + toVersion
                + "-"
                + UUID.randomUUID()
                + ".bak");
    try {
      backup.backup(connection, destination);
      if (!Files.isRegularFile(destination) || Files.size(destination) == 0L) {
        throw new IOException("backup missing");
      }
    } catch (IOException | SQLException exception) {
      try {
        Files.deleteIfExists(destination);
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw JavaMemoryRepositoryException.backupFailed(exception);
    }
  }

  private static void sqliteBackup(Connection source, Path destination) throws SQLException {
    SQLiteConnection sqlite = source.unwrap(SQLiteConnection.class);
    int result = sqlite.getDatabase().backup("main", destination.toString(), null);
    if (result != SQLiteErrorCode.SQLITE_OK.code) {
      throw new SQLException("SQLite Backup failed with code " + result);
    }
  }

  private static void requireHealthyDatabase(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var row = statement.executeQuery("PRAGMA quick_check(1)")) {
      if (!row.next() || !"ok".equalsIgnoreCase(row.getString(1)) || row.next()) {
        throw JavaMemoryRepositoryException.databaseUnavailable(null);
      }
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
      try {
        connection.setAutoCommit(true);
      } catch (SQLException ignored) {
        // Connection closes immediately; a prior failure remains the primary signal.
      }
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run() throws SQLException;
  }
}
