package io.namei.agent.adapter.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict V2 extension for scope-bound soft supersede and its redacted batch mutation result. */
final class JavaMemorySchemaV2 {
  static final int VERSION = 2;
  static final Set<String> TABLES =
      Set.of("memory_schema", "memory_items", "memory_mutations", "memory_mutation_items");

  private static final String ADD_ITEM_STATUS =
      """
      ALTER TABLE memory_items ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUPERSEDED'))
      """;
  private static final String CREATE_STATUS_INDEX =
      """
      CREATE INDEX ix_memory_items_scope_status_updated
        ON memory_items(scope_binding, status, updated_at DESC, id ASC)
      """;
  private static final String CREATE_MUTATION_ITEMS =
      """
      CREATE TABLE memory_mutation_items (
        mutation_id INTEGER NOT NULL,
        ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
        item_id TEXT NOT NULL,
        result_status TEXT NOT NULL,
        PRIMARY KEY (mutation_id, ordinal),
        FOREIGN KEY (mutation_id) REFERENCES memory_mutations(id) ON DELETE CASCADE
      )
      """;

  private static final List<JavaMemorySchemaV1.ColumnSpec> ITEM_COLUMNS = itemColumns();
  private static final List<JavaMemorySchemaV1.ColumnSpec> MUTATION_ITEM_COLUMNS =
      List.of(
          new JavaMemorySchemaV1.ColumnSpec("mutation_id", "INTEGER", true, true, null),
          new JavaMemorySchemaV1.ColumnSpec("ordinal", "INTEGER", true, true, null),
          new JavaMemorySchemaV1.ColumnSpec("item_id", "TEXT", true, false, null),
          new JavaMemorySchemaV1.ColumnSpec("result_status", "TEXT", true, false, null));

  private JavaMemorySchemaV2() {}

  static void createNew(Connection connection, Instant now) throws SQLException {
    JavaMemorySchemaV1.createNew(connection, now);
    migrateV1(connection, now);
  }

  static void migrateV1(Connection connection, Instant now) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(ADD_ITEM_STATUS);
      statement.executeUpdate(CREATE_STATUS_INDEX);
      statement.executeUpdate(CREATE_MUTATION_ITEMS);
    }
    try (var update =
        connection.prepareStatement(
            "UPDATE memory_schema SET version = ?, updated_at = ? WHERE singleton = 1 AND version ="
                + " 1")) {
      update.setInt(1, VERSION);
      update.setString(2, now.toString());
      if (update.executeUpdate() != 1) {
        throw JavaMemoryRepositoryException.schemaIncompatible();
      }
    }
  }

  static void validateV2(Connection connection) throws SQLException {
    if (!JavaMemorySchemaV1.userTables(connection).equals(TABLES)) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
    JavaMemorySchemaV1.forbidViewsAndTriggers(connection);
    JavaMemorySchemaV1.validateHeader(connection);
    JavaMemorySchemaV1.requireColumns(connection, "memory_items", ITEM_COLUMNS);
    JavaMemorySchemaV1.requireColumns(
        connection, "memory_mutations", JavaMemorySchemaV1.MUTATION_COLUMNS);
    JavaMemorySchemaV1.requireColumns(connection, "memory_mutation_items", MUTATION_ITEM_COLUMNS);
    JavaMemorySchemaV1.requireSqlContains(
        connection,
        "memory_items",
        "STATUS TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (STATUS IN ('ACTIVE', 'SUPERSEDED'))");
    JavaMemorySchemaV1.requireSqlContains(
        connection, "memory_mutation_items", "PRIMARY KEY (MUTATION_ID, ORDINAL)");
    JavaMemorySchemaV1.requireSqlContains(
        connection,
        "memory_mutation_items",
        "FOREIGN KEY (MUTATION_ID) REFERENCES MEMORY_MUTATIONS(ID) ON DELETE CASCADE");
    JavaMemorySchemaV1.requireUniqueColumns(
        connection, "memory_items", List.of("scope_binding", "memory_type", "content_hash"));
    JavaMemorySchemaV1.requireUniqueColumns(
        connection, "memory_mutations", List.of("scope_binding", "request_id"));
    JavaMemorySchemaV1.requireOrderedIndex(
        connection,
        "memory_items",
        "ix_memory_items_scope_updated",
        List.of("scope_binding ASC", "updated_at DESC", "id ASC"));
    JavaMemorySchemaV1.requireOrderedIndex(
        connection,
        "memory_items",
        "ix_memory_items_scope_status_updated",
        List.of("scope_binding ASC", "status ASC", "updated_at DESC", "id ASC"));
    JavaMemorySchemaV1.SchemaRecord record = JavaMemorySchemaV1.readSchemaRecord(connection);
    if (record.version() != VERSION) {
      throw JavaMemoryRepositoryException.schemaIncompatible();
    }
  }

  private static List<JavaMemorySchemaV1.ColumnSpec> itemColumns() {
    var columns = new ArrayList<>(JavaMemorySchemaV1.ITEM_COLUMNS);
    columns.add(new JavaMemorySchemaV1.ColumnSpec("status", "TEXT", true, false, "'ACTIVE'"));
    return List.copyOf(columns);
  }
}
