package io.namei.agent.adapter.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class JavaMemorySchemaV1 {
  static final int VERSION = 1;
  static final Set<String> TABLES = Set.of("memory_schema", "memory_items", "memory_mutations");

  private static final String CREATE_SCHEMA =
      """
      CREATE TABLE memory_schema (
        singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
        version INTEGER NOT NULL,
        updated_at TEXT NOT NULL
      )
      """;
  private static final String CREATE_ITEMS =
      """
      CREATE TABLE memory_items (
        id TEXT PRIMARY KEY,
        scope_binding TEXT NOT NULL,
        memory_type TEXT NOT NULL,
        content TEXT NOT NULL,
        content_hash TEXT NOT NULL,
        embedding BLOB NOT NULL,
        embedding_model TEXT NOT NULL,
        embedding_dimensions INTEGER NOT NULL,
        reinforcement INTEGER NOT NULL DEFAULT 1,
        emotional_weight INTEGER NOT NULL DEFAULT 0,
        source_kind TEXT NOT NULL,
        happened_at TEXT,
        revision INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        UNIQUE(scope_binding, memory_type, content_hash)
      )
      """;
  private static final String CREATE_ITEMS_INDEX =
      """
      CREATE INDEX ix_memory_items_scope_updated
        ON memory_items(scope_binding, updated_at DESC, id ASC)
      """;
  private static final String CREATE_MUTATIONS =
      """
      CREATE TABLE memory_mutations (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        scope_binding TEXT NOT NULL,
        request_id TEXT NOT NULL,
        operation TEXT NOT NULL,
        argument_hash TEXT NOT NULL,
        item_id TEXT,
        result_status TEXT NOT NULL,
        created_at TEXT NOT NULL,
        UNIQUE(scope_binding, request_id)
      )
      """;

  static final List<ColumnSpec> SCHEMA_COLUMNS =
      List.of(
          new ColumnSpec("singleton", "INTEGER", false, true, null),
          new ColumnSpec("version", "INTEGER", true, false, null),
          new ColumnSpec("updated_at", "TEXT", true, false, null));
  static final List<ColumnSpec> ITEM_COLUMNS =
      List.of(
          new ColumnSpec("id", "TEXT", false, true, null),
          new ColumnSpec("scope_binding", "TEXT", true, false, null),
          new ColumnSpec("memory_type", "TEXT", true, false, null),
          new ColumnSpec("content", "TEXT", true, false, null),
          new ColumnSpec("content_hash", "TEXT", true, false, null),
          new ColumnSpec("embedding", "BLOB", true, false, null),
          new ColumnSpec("embedding_model", "TEXT", true, false, null),
          new ColumnSpec("embedding_dimensions", "INTEGER", true, false, null),
          new ColumnSpec("reinforcement", "INTEGER", true, false, "1"),
          new ColumnSpec("emotional_weight", "INTEGER", true, false, "0"),
          new ColumnSpec("source_kind", "TEXT", true, false, null),
          new ColumnSpec("happened_at", "TEXT", false, false, null),
          new ColumnSpec("revision", "INTEGER", true, false, "1"),
          new ColumnSpec("created_at", "TEXT", true, false, null),
          new ColumnSpec("updated_at", "TEXT", true, false, null));
  static final List<ColumnSpec> MUTATION_COLUMNS =
      List.of(
          new ColumnSpec("id", "INTEGER", false, true, null),
          new ColumnSpec("scope_binding", "TEXT", true, false, null),
          new ColumnSpec("request_id", "TEXT", true, false, null),
          new ColumnSpec("operation", "TEXT", true, false, null),
          new ColumnSpec("argument_hash", "TEXT", true, false, null),
          new ColumnSpec("item_id", "TEXT", false, false, null),
          new ColumnSpec("result_status", "TEXT", true, false, null),
          new ColumnSpec("created_at", "TEXT", true, false, null));

  private JavaMemorySchemaV1() {}

  static void createNew(Connection connection, Instant now) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(CREATE_SCHEMA);
      createDataTables(statement);
    }
    insertVersion(connection, VERSION, now);
  }

  static void migrateV0(Connection connection, Instant now) throws SQLException {
    try (var statement = connection.createStatement()) {
      createDataTables(statement);
    }
    try (var update =
        connection.prepareStatement(
            "UPDATE memory_schema SET version = ?, updated_at = ? WHERE singleton = 1 AND version ="
                + " 0")) {
      update.setInt(1, VERSION);
      update.setString(2, now.toString());
      if (update.executeUpdate() != 1) {
        throw JavaMemoryRepositoryException.schemaIncompatible();
      }
    }
  }

  static Set<String> userTables(Connection connection) throws SQLException {
    var tables = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE"
                    + " 'sqlite_%'")) {
      while (rows.next()) {
        tables.add(rows.getString(1));
      }
    }
    return Set.copyOf(tables);
  }

  static void validateV0(Connection connection) throws SQLException {
    if (!userTables(connection).equals(Set.of("memory_schema"))) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
    forbidViewsAndTriggers(connection);
    validateHeader(connection);
    SchemaRecord record = readSchemaRecord(connection);
    if (record.version() != 0) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
  }

  static void validateV1(Connection connection) throws SQLException {
    if (!userTables(connection).equals(TABLES)) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
    forbidViewsAndTriggers(connection);
    validateHeader(connection);
    requireColumns(connection, "memory_items", ITEM_COLUMNS);
    requireColumns(connection, "memory_mutations", MUTATION_COLUMNS);
    requireSqlContains(connection, "memory_mutations", "ID INTEGER PRIMARY KEY AUTOINCREMENT");
    requireUniqueColumns(
        connection, "memory_items", List.of("scope_binding", "memory_type", "content_hash"));
    requireUniqueColumns(connection, "memory_mutations", List.of("scope_binding", "request_id"));
    requireOrderedIndex(
        connection,
        "memory_items",
        "ix_memory_items_scope_updated",
        List.of("scope_binding ASC", "updated_at DESC", "id ASC"));
    SchemaRecord record = readSchemaRecord(connection);
    if (record.version() != VERSION) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
  }

  static SchemaRecord readSchemaRecord(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT singleton, version, updated_at FROM memory_schema ORDER BY singleton")) {
      if (!rows.next() || rows.getInt("singleton") != 1) {
        throw JavaMemoryRepositoryException.schemaIncompatible();
      }
      long storedVersion = rows.getLong("version");
      if (rows.wasNull()
          || storedVersion < Integer.MIN_VALUE
          || storedVersion > Integer.MAX_VALUE) {
        throw JavaMemoryRepositoryException.schemaIncompatible();
      }
      int version = Math.toIntExact(storedVersion);
      String updatedAt = rows.getString("updated_at");
      if (rows.next()) {
        throw JavaMemoryRepositoryException.schemaIncompatible();
      }
      try {
        return new SchemaRecord(version, Instant.parse(updatedAt));
      } catch (DateTimeParseException | NullPointerException exception) {
        throw JavaMemoryRepositoryException.schemaIncompatible();
      }
    }
  }

  static void validateHeader(Connection connection) throws SQLException {
    requireColumns(connection, "memory_schema", SCHEMA_COLUMNS);
    requireSqlContains(connection, "memory_schema", "CHECK (SINGLETON = 1)");
  }

  private static void createDataTables(java.sql.Statement statement) throws SQLException {
    statement.executeUpdate(CREATE_ITEMS);
    statement.executeUpdate(CREATE_ITEMS_INDEX);
    statement.executeUpdate(CREATE_MUTATIONS);
  }

  private static void insertVersion(Connection connection, int version, Instant now)
      throws SQLException {
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO memory_schema (singleton, version, updated_at) VALUES (1, ?, ?)")) {
      insert.setInt(1, version);
      insert.setString(2, now.toString());
      insert.executeUpdate();
    }
  }

  static void requireColumns(Connection connection, String table, List<ColumnSpec> expected)
      throws SQLException {
    var actual = new ArrayList<ColumnSpec>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        actual.add(
            new ColumnSpec(
                rows.getString("name"),
                rows.getString("type").toUpperCase(Locale.ROOT),
                rows.getBoolean("notnull"),
                rows.getBoolean("pk"),
                rows.getString("dflt_value")));
      }
    }
    if (!actual.equals(expected)) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
  }

  static void requireSqlContains(Connection connection, String name, String fragment)
      throws SQLException {
    String sql;
    try (var statement =
        connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name = ?")) {
      statement.setString(1, name);
      try (var row = statement.executeQuery()) {
        if (!row.next()) {
          throw JavaMemoryRepositoryException.schemaIncompatible();
        }
        sql = row.getString(1);
      }
    }
    String normalized = sql == null ? "" : sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    if (!normalized.contains(fragment)) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
  }

  static void requireUniqueColumns(Connection connection, String table, List<String> expected)
      throws SQLException {
    for (List<String> columns : uniqueIndexes(connection, table)) {
      if (columns.equals(expected)) {
        return;
      }
    }
    throw JavaMemoryRepositoryException.schemaIncompatible();
  }

  private static List<List<String>> uniqueIndexes(Connection connection, String table)
      throws SQLException {
    var result = new ArrayList<List<String>>();
    try (var statement = connection.createStatement();
        var indexes = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
      while (indexes.next()) {
        if (indexes.getBoolean("unique")) {
          result.add(indexColumns(connection, indexes.getString("name"), false));
        }
      }
    }
    return result;
  }

  static void requireOrderedIndex(
      Connection connection, String table, String index, List<String> expected)
      throws SQLException {
    Map<String, Boolean> indexes = new HashMap<>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
      while (rows.next()) {
        indexes.put(rows.getString("name"), rows.getBoolean("unique"));
      }
    }
    if (!Boolean.FALSE.equals(indexes.get(index))
        || !indexColumns(connection, index, true).equals(expected)) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
  }

  private static List<String> indexColumns(
      Connection connection, String index, boolean includeOrder) throws SQLException {
    var columns = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery("PRAGMA index_xinfo('" + index.replace("'", "''") + "')")) {
      while (rows.next()) {
        if (rows.getInt("key") == 1 && rows.getInt("cid") >= 0) {
          String column = rows.getString("name");
          columns.add(
              includeOrder ? column + (rows.getBoolean("desc") ? " DESC" : " ASC") : column);
        }
      }
    }
    return List.copyOf(columns);
  }

  static void forbidViewsAndTriggers(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT 1 FROM sqlite_schema WHERE type IN ('view', 'trigger') LIMIT 1")) {
      if (rows.next()) {
        throw JavaMemoryRepositoryException.schemaIncompatible();
      }
    }
  }

  record SchemaRecord(int version, Instant updatedAt) {}

  record ColumnSpec(
      String name, String type, boolean notNull, boolean primaryKey, String defaultValue) {}
}
