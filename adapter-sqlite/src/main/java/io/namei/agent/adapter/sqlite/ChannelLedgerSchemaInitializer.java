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
import java.util.function.Supplier;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;

public final class ChannelLedgerSchemaInitializer {
  private static final String DATABASE_FILE_NAME = "channel-ledger.db";

  private final Path database;
  private final String jdbcUrl;
  private final int busyTimeoutMillis;
  private final Clock clock;
  private final ChannelLedgerBackup backup;
  private final Supplier<UUID> backupId;
  private final ReentrantLock initializationGate = new ReentrantLock();

  public ChannelLedgerSchemaInitializer(Path database, int busyTimeoutMillis) {
    this(
        database,
        busyTimeoutMillis,
        Clock.systemUTC(),
        ChannelLedgerSchemaInitializer::sqliteBackup,
        UUID::randomUUID);
  }

  ChannelLedgerSchemaInitializer(
      Path database,
      int busyTimeoutMillis,
      Clock clock,
      ChannelLedgerBackup backup,
      Supplier<UUID> backupId) {
    Objects.requireNonNull(database, "database");
    if (database.getFileName() == null
        || !DATABASE_FILE_NAME.equals(database.getFileName().toString())) {
      throw new IllegalArgumentException("渠道账本数据库文件名必须为 channel-ledger.db");
    }
    if (busyTimeoutMillis < 1 || busyTimeoutMillis > 60_000) {
      throw new IllegalArgumentException("SQLite Busy Timeout 必须在 1..60000");
    }
    this.database = database.toAbsolutePath().normalize();
    this.jdbcUrl = "jdbc:sqlite:" + this.database;
    this.busyTimeoutMillis = busyTimeoutMillis;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.backup = Objects.requireNonNull(backup, "backup");
    this.backupId = Objects.requireNonNull(backupId, "backupId");
  }

  public void initialize() {
    initializationGate.lock();
    try {
      Path parent = database.getParent();
      if (parent == null) {
        throw new IOException("database parent missing");
      }
      Files.createDirectories(parent);
      try (var connection = openConfiguredConnection()) {
        requireHealthyDatabase(connection);
        var tables = ChannelLedgerSchemaV1.userTables(connection);
        if (tables.isEmpty()) {
          ChannelLedgerSchemaV1.validateEmpty(connection);
          setJournalModeWal(connection);
          transaction(
              connection,
              () -> {
                ChannelLedgerSchemaV1.createNew(connection, clock.instant());
                ChannelLedgerSchemaV1.validateV1(connection);
              });
          return;
        }
        if (!tables.contains("channel_schema")) {
          throw ChannelLedgerRepositoryException.schemaIncompatible();
        }

        ChannelLedgerSchemaV1.validateHeader(connection);
        ChannelLedgerSchemaV1.SchemaRecord schema =
            ChannelLedgerSchemaV1.readSchemaRecord(connection);
        if (schema.version() < 0 || schema.version() > ChannelLedgerSchemaV1.VERSION) {
          throw ChannelLedgerRepositoryException.schemaIncompatible();
        }
        if (schema.version() == ChannelLedgerSchemaV1.VERSION) {
          ChannelLedgerSchemaV1.validateV1(connection);
          requireJournalModeWal(connection);
          return;
        }

        ChannelLedgerSchemaV1.validateV0(connection);
        backupV0(connection);
        setJournalModeWal(connection);
        transaction(
            connection,
            () -> {
              ChannelLedgerSchemaV1.migrateV0(connection, clock.instant());
              ChannelLedgerSchemaV1.validateV1(connection);
            });
      }
    } catch (ChannelLedgerRepositoryException exception) {
      throw exception;
    } catch (IOException | SQLException exception) {
      throw ChannelLedgerRepositoryException.databaseUnavailable(exception);
    } finally {
      initializationGate.unlock();
    }
  }

  public Connection openConnection() throws SQLException {
    Connection connection = openConfiguredConnection();
    try {
      requireJournalModeWal(connection);
      return connection;
    } catch (SQLException | RuntimeException exception) {
      try {
        connection.close();
      } catch (SQLException closeFailure) {
        exception.addSuppressed(closeFailure);
      }
      throw exception;
    }
  }

  Path database() {
    return database;
  }

  private Connection openConfiguredConnection() throws SQLException {
    Connection connection = DriverManager.getConnection(jdbcUrl);
    try {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
        statement.execute("PRAGMA foreign_keys=ON");
        statement.execute("PRAGMA synchronous=FULL");
      }
      requirePragma(connection, "busy_timeout", busyTimeoutMillis);
      requirePragma(connection, "foreign_keys", 1);
      requirePragma(connection, "synchronous", 2);
      return connection;
    } catch (SQLException | RuntimeException exception) {
      try {
        connection.close();
      } catch (SQLException closeFailure) {
        exception.addSuppressed(closeFailure);
      }
      throw exception;
    }
  }

  private void backupV0(Connection source) {
    Path destination = null;
    try {
      UUID id = Objects.requireNonNull(backupId.get(), "backupId value");
      destination = database.resolveSibling(DATABASE_FILE_NAME + ".v0-to-v1-" + id + ".bak");
      if (Files.exists(destination)) {
        throw new IOException("backup destination exists");
      }
      backup.backup(source, destination);
      if (!Files.isRegularFile(destination) || Files.size(destination) == 0L) {
        throw new IOException("backup missing");
      }
      validateV0Backup(destination);
    } catch (Exception exception) {
      if (destination != null) {
        try {
          Files.deleteIfExists(destination);
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw ChannelLedgerRepositoryException.backupFailed(exception);
    }
  }

  private static void validateV0Backup(Path destination) throws SQLException {
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + destination.toAbsolutePath().normalize())) {
      requireHealthyDatabase(connection);
      ChannelLedgerSchemaV1.validateV0(connection);
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
        var rows = statement.executeQuery("PRAGMA quick_check(1)")) {
      if (!rows.next() || !"ok".equalsIgnoreCase(rows.getString(1)) || rows.next()) {
        throw ChannelLedgerRepositoryException.databaseUnavailable(null);
      }
    }
  }

  private static void setJournalModeWal(Connection connection) throws SQLException {
    String mode;
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
      mode = rows.next() ? rows.getString(1) : null;
      if (rows.next()) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
    }
    if (!"wal".equalsIgnoreCase(mode)) {
      throw ChannelLedgerRepositoryException.schemaIncompatible();
    }
  }

  private static void requireJournalModeWal(Connection connection) throws SQLException {
    String mode;
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA journal_mode")) {
      mode = rows.next() ? rows.getString(1) : null;
      if (rows.next()) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
    }
    if (!"wal".equalsIgnoreCase(mode)) {
      throw ChannelLedgerRepositoryException.schemaIncompatible();
    }
  }

  private static void requirePragma(Connection connection, String name, int expected)
      throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA " + name)) {
      if (!rows.next() || rows.getInt(1) != expected || rows.next()) {
        throw new SQLException("SQLite PRAGMA rejected");
      }
    }
  }

  private static void transaction(Connection connection, SqlWork work) throws SQLException {
    boolean started = false;
    try (var statement = connection.createStatement()) {
      statement.execute("BEGIN IMMEDIATE");
      started = true;
      work.run();
      statement.execute("COMMIT");
      started = false;
    } catch (SQLException | RuntimeException exception) {
      if (started) {
        try (var rollback = connection.createStatement()) {
          rollback.execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
          exception.addSuppressed(rollbackFailure);
        }
      }
      throw exception;
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run() throws SQLException;
  }
}
