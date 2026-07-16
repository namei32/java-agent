package io.namei.agent.adapter.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ChannelLedgerSchemaV1 {
  static final int VERSION = 1;

  private static final String CREATE_SCHEMA =
      """
      CREATE TABLE channel_schema (
        singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
        version INTEGER NOT NULL CHECK (version >= 0),
        updated_at TEXT NOT NULL
      )
      """;
  private static final String CREATE_CURSORS =
      """
      CREATE TABLE channel_cursors (
        channel TEXT NOT NULL CHECK (length(channel) BETWEEN 1 AND 32),
        instance_id TEXT NOT NULL CHECK (
          length(instance_id) = 64 AND instance_id NOT GLOB '*[^0-9a-f]*'
        ),
        next_sequence INTEGER NOT NULL CHECK (next_sequence >= 0),
        revision INTEGER NOT NULL CHECK (revision >= 0),
        updated_at TEXT NOT NULL CHECK (length(updated_at) BETWEEN 20 AND 35),
        PRIMARY KEY (channel, instance_id)
      )
      """;
  private static final String CREATE_CLAIMS =
      """
      CREATE TABLE channel_turn_claims (
        channel TEXT NOT NULL CHECK (length(channel) BETWEEN 1 AND 32),
        instance_id TEXT NOT NULL CHECK (
          length(instance_id) = 64 AND instance_id NOT GLOB '*[^0-9a-f]*'
        ),
        external_message_id TEXT NOT NULL CHECK (length(external_message_id) BETWEEN 1 AND 128),
        request_fingerprint TEXT NOT NULL CHECK (
          length(request_fingerprint) = 64
            AND request_fingerprint NOT GLOB '*[^0-9a-f]*'
        ),
        turn_id TEXT NOT NULL UNIQUE CHECK (length(turn_id) BETWEEN 1 AND 128),
        state TEXT NOT NULL CHECK (
          state IN (
            'RESERVED', 'START_RETRYABLE', 'RUNNING',
            'TERMINAL_RECORDED', 'EXECUTION_UNKNOWN'
          )
        ),
        start_attempts INTEGER NOT NULL CHECK (start_attempts BETWEEN 0 AND 3),
        owner_id TEXT CHECK (
          owner_id IS NULL
            OR (length(owner_id) = 32 AND owner_id NOT GLOB '*[^0-9a-f]*')
        ),
        lease_expires_at TEXT CHECK (
          lease_expires_at IS NULL OR length(lease_expires_at) BETWEEN 20 AND 35
        ),
        revision INTEGER NOT NULL CHECK (revision >= 0),
        created_at TEXT NOT NULL CHECK (length(created_at) BETWEEN 20 AND 35),
        updated_at TEXT NOT NULL CHECK (length(updated_at) BETWEEN 20 AND 35),
        PRIMARY KEY (channel, instance_id, external_message_id),
        CHECK (
          (state = 'RUNNING' AND owner_id IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR (state <> 'RUNNING' AND owner_id IS NULL AND lease_expires_at IS NULL)
        )
      )
      """;
  private static final String CREATE_EVENTS =
      """
      CREATE TABLE channel_inbox_events (
        channel TEXT NOT NULL CHECK (length(channel) BETWEEN 1 AND 32),
        instance_id TEXT NOT NULL CHECK (
          length(instance_id) = 64 AND instance_id NOT GLOB '*[^0-9a-f]*'
        ),
        external_event_id TEXT NOT NULL CHECK (length(external_event_id) BETWEEN 1 AND 128),
        external_sequence INTEGER NOT NULL CHECK (external_sequence >= 0),
        event_fingerprint TEXT NOT NULL CHECK (
          length(event_fingerprint) = 64 AND event_fingerprint NOT GLOB '*[^0-9a-f]*'
        ),
        decision TEXT NOT NULL CHECK (
          decision IN ('IGNORED', 'CONTROL', 'FEEDBACK_QUEUED', 'TURN_RESERVED', 'DUPLICATE')
        ),
        turn_id TEXT CHECK (turn_id IS NULL OR length(turn_id) BETWEEN 1 AND 128),
        created_at TEXT NOT NULL CHECK (length(created_at) BETWEEN 20 AND 35),
        updated_at TEXT NOT NULL CHECK (length(updated_at) BETWEEN 20 AND 35),
        PRIMARY KEY (channel, instance_id, external_event_id),
        UNIQUE (channel, instance_id, external_sequence),
        FOREIGN KEY (turn_id) REFERENCES channel_turn_claims(turn_id)
          ON DELETE RESTRICT ON UPDATE RESTRICT
      )
      """;
  private static final String CREATE_DELIVERIES =
      """
      CREATE TABLE channel_deliveries (
        delivery_id TEXT NOT NULL PRIMARY KEY CHECK (length(delivery_id) BETWEEN 1 AND 128),
        channel TEXT NOT NULL CHECK (length(channel) BETWEEN 1 AND 32),
        instance_id TEXT NOT NULL CHECK (
          length(instance_id) = 64 AND instance_id NOT GLOB '*[^0-9a-f]*'
        ),
        target_id TEXT NOT NULL CHECK (length(target_id) BETWEEN 1 AND 256),
        source_kind TEXT NOT NULL CHECK (source_kind IN ('TURN_TERMINAL', 'CHANNEL_FEEDBACK')),
        correlation_id TEXT NOT NULL CHECK (length(correlation_id) BETWEEN 1 AND 128),
        message_type TEXT NOT NULL CHECK (
          message_type IN (
            'TURN_COMPLETED', 'TURN_CANCELLED', 'TURN_FAILED',
            'SESSION_BUSY', 'NO_ACTIVE_TURN'
          )
        ),
        payload_fingerprint TEXT NOT NULL CHECK (
          length(payload_fingerprint) = 64
            AND payload_fingerprint NOT GLOB '*[^0-9a-f]*'
        ),
        state TEXT NOT NULL CHECK (state IN ('PENDING', 'DELIVERING', 'DELIVERED', 'FAILED', 'UNKNOWN')),
        part_count INTEGER NOT NULL CHECK (part_count BETWEEN 1 AND 16),
        next_part_index INTEGER NOT NULL CHECK (
          next_part_index >= 0 AND next_part_index <= part_count
        ),
        payload_pruned INTEGER NOT NULL CHECK (payload_pruned IN (0, 1)),
        owner_id TEXT CHECK (
          owner_id IS NULL
            OR (length(owner_id) = 32 AND owner_id NOT GLOB '*[^0-9a-f]*')
        ),
        lease_expires_at TEXT CHECK (
          lease_expires_at IS NULL OR length(lease_expires_at) BETWEEN 20 AND 35
        ),
        last_error_code TEXT CHECK (
          last_error_code IS NULL
            OR (length(last_error_code) BETWEEN 1 AND 64 AND last_error_code = upper(last_error_code))
        ),
        revision INTEGER NOT NULL CHECK (revision >= 0),
        created_at TEXT NOT NULL CHECK (length(created_at) BETWEEN 20 AND 35),
        updated_at TEXT NOT NULL CHECK (length(updated_at) BETWEEN 20 AND 35),
        UNIQUE (channel, instance_id, source_kind, correlation_id),
        CHECK (
          (source_kind = 'TURN_TERMINAL'
            AND message_type IN ('TURN_COMPLETED', 'TURN_CANCELLED', 'TURN_FAILED'))
          OR (source_kind = 'CHANNEL_FEEDBACK'
            AND message_type IN ('SESSION_BUSY', 'NO_ACTIVE_TURN'))
        ),
        CHECK (
          (state = 'DELIVERING' AND owner_id IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR (state <> 'DELIVERING' AND owner_id IS NULL AND lease_expires_at IS NULL)
        ),
        CHECK (payload_pruned = 0 OR state IN ('DELIVERED', 'FAILED')),
        CHECK (
          (state IN ('FAILED', 'UNKNOWN') AND last_error_code IS NOT NULL)
            OR (state NOT IN ('FAILED', 'UNKNOWN') AND last_error_code IS NULL)
        )
      )
      """;
  private static final String CREATE_PARTS =
      """
      CREATE TABLE channel_delivery_parts (
        delivery_id TEXT NOT NULL CHECK (length(delivery_id) BETWEEN 1 AND 128),
        part_index INTEGER NOT NULL CHECK (part_index BETWEEN 0 AND 15),
        payload_text TEXT NOT NULL CHECK (length(payload_text) BETWEEN 1 AND 4000),
        payload_fingerprint TEXT NOT NULL CHECK (
          length(payload_fingerprint) = 64
            AND payload_fingerprint NOT GLOB '*[^0-9a-f]*'
        ),
        state TEXT NOT NULL CHECK (
          state IN ('PENDING', 'IN_FLIGHT', 'RETRY_WAIT', 'DELIVERED', 'FAILED', 'UNKNOWN')
        ),
        attempt_count INTEGER NOT NULL CHECK (attempt_count BETWEEN 0 AND 2),
        next_attempt_at TEXT CHECK (
          next_attempt_at IS NULL OR length(next_attempt_at) BETWEEN 20 AND 35
        ),
        remote_message_id TEXT CHECK (
          remote_message_id IS NULL OR length(remote_message_id) BETWEEN 1 AND 128
        ),
        last_error_code TEXT CHECK (
          last_error_code IS NULL
            OR (length(last_error_code) BETWEEN 1 AND 64 AND last_error_code = upper(last_error_code))
        ),
        updated_at TEXT NOT NULL CHECK (length(updated_at) BETWEEN 20 AND 35),
        PRIMARY KEY (delivery_id, part_index),
        FOREIGN KEY (delivery_id) REFERENCES channel_deliveries(delivery_id)
          ON DELETE CASCADE ON UPDATE RESTRICT,
        CHECK (
          (state = 'RETRY_WAIT' AND next_attempt_at IS NOT NULL)
            OR (state <> 'RETRY_WAIT' AND next_attempt_at IS NULL)
        ),
        CHECK (
          (state = 'DELIVERED' AND remote_message_id IS NOT NULL)
            OR (state <> 'DELIVERED' AND remote_message_id IS NULL)
        ),
        CHECK (
          (state IN ('RETRY_WAIT', 'FAILED', 'UNKNOWN') AND last_error_code IS NOT NULL)
            OR (state NOT IN ('RETRY_WAIT', 'FAILED', 'UNKNOWN') AND last_error_code IS NULL)
        )
      )
      """;
  private static final String CREATE_ATTEMPTS =
      """
      CREATE TABLE channel_delivery_attempts (
        delivery_id TEXT NOT NULL CHECK (length(delivery_id) BETWEEN 1 AND 128),
        part_index INTEGER NOT NULL CHECK (part_index BETWEEN 0 AND 15),
        attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 2),
        started_at TEXT NOT NULL CHECK (length(started_at) BETWEEN 20 AND 35),
        completed_at TEXT CHECK (
          completed_at IS NULL OR length(completed_at) BETWEEN 20 AND 35
        ),
        outcome TEXT NOT NULL CHECK (
          outcome IN (
            'STARTED', 'SUCCEEDED', 'RETRYABLE_REJECTED',
            'PERMANENT_REJECTED', 'UNKNOWN'
          )
        ),
        remote_message_id TEXT CHECK (
          remote_message_id IS NULL OR length(remote_message_id) BETWEEN 1 AND 128
        ),
        error_code TEXT CHECK (
          error_code IS NULL
            OR (length(error_code) BETWEEN 1 AND 64 AND error_code = upper(error_code))
        ),
        PRIMARY KEY (delivery_id, part_index, attempt_number),
        FOREIGN KEY (delivery_id, part_index)
          REFERENCES channel_delivery_parts(delivery_id, part_index)
          ON DELETE CASCADE ON UPDATE RESTRICT,
        CHECK (
          (outcome = 'STARTED' AND completed_at IS NULL
            AND remote_message_id IS NULL AND error_code IS NULL)
          OR (outcome = 'SUCCEEDED' AND completed_at IS NOT NULL
            AND remote_message_id IS NOT NULL AND error_code IS NULL)
          OR (outcome IN ('RETRYABLE_REJECTED', 'PERMANENT_REJECTED', 'UNKNOWN')
            AND completed_at IS NOT NULL AND remote_message_id IS NULL AND error_code IS NOT NULL)
        )
      )
      """;

  private static final String CREATE_INBOX_TURN_INDEX =
      """
      CREATE INDEX ix_channel_inbox_turn
        ON channel_inbox_events(turn_id ASC)
      """;
  private static final String CREATE_CLAIMS_RECOVERY_INDEX =
      """
      CREATE INDEX ix_channel_claims_recovery
        ON channel_turn_claims(channel ASC, instance_id ASC, state ASC, updated_at ASC, turn_id ASC)
      """;
  private static final String CREATE_DELIVERIES_DUE_INDEX =
      """
      CREATE INDEX ix_channel_deliveries_due
        ON channel_deliveries(channel ASC, instance_id ASC, state ASC, created_at ASC, delivery_id ASC)
      """;
  private static final String CREATE_PARTS_DUE_INDEX =
      """
      CREATE INDEX ix_channel_parts_due
        ON channel_delivery_parts(state ASC, next_attempt_at ASC, delivery_id ASC, part_index ASC)
      """;
  private static final String CREATE_ATTEMPTS_OUTCOME_INDEX =
      """
      CREATE INDEX ix_channel_attempts_outcome
        ON channel_delivery_attempts(delivery_id ASC, outcome ASC)
      """;

  static final Set<String> TABLES =
      Set.of(
          "channel_schema",
          "channel_cursors",
          "channel_inbox_events",
          "channel_turn_claims",
          "channel_deliveries",
          "channel_delivery_parts",
          "channel_delivery_attempts");

  private static final Map<String, String> TABLE_DEFINITIONS =
      Map.ofEntries(
          Map.entry("channel_schema", CREATE_SCHEMA),
          Map.entry("channel_cursors", CREATE_CURSORS),
          Map.entry("channel_turn_claims", CREATE_CLAIMS),
          Map.entry("channel_inbox_events", CREATE_EVENTS),
          Map.entry("channel_deliveries", CREATE_DELIVERIES),
          Map.entry("channel_delivery_parts", CREATE_PARTS),
          Map.entry("channel_delivery_attempts", CREATE_ATTEMPTS));
  private static final Map<String, String> INDEX_DEFINITIONS =
      Map.of(
          "ix_channel_inbox_turn", CREATE_INBOX_TURN_INDEX,
          "ix_channel_claims_recovery", CREATE_CLAIMS_RECOVERY_INDEX,
          "ix_channel_deliveries_due", CREATE_DELIVERIES_DUE_INDEX,
          "ix_channel_parts_due", CREATE_PARTS_DUE_INDEX,
          "ix_channel_attempts_outcome", CREATE_ATTEMPTS_OUTCOME_INDEX);

  private static final Map<String, List<ForeignKeySpec>> FOREIGN_KEYS =
      Map.of(
          "channel_inbox_events",
              List.of(
                  new ForeignKeySpec(
                      0, 0, "channel_turn_claims", "turn_id", "turn_id", "RESTRICT", "RESTRICT")),
          "channel_delivery_parts",
              List.of(
                  new ForeignKeySpec(
                      0,
                      0,
                      "channel_deliveries",
                      "delivery_id",
                      "delivery_id",
                      "RESTRICT",
                      "CASCADE")),
          "channel_delivery_attempts",
              List.of(
                  new ForeignKeySpec(
                      0,
                      0,
                      "channel_delivery_parts",
                      "delivery_id",
                      "delivery_id",
                      "RESTRICT",
                      "CASCADE"),
                  new ForeignKeySpec(
                      0,
                      1,
                      "channel_delivery_parts",
                      "part_index",
                      "part_index",
                      "RESTRICT",
                      "CASCADE")));

  private ChannelLedgerSchemaV1() {}

  static void createNew(Connection connection, Instant now) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(CREATE_SCHEMA);
      createDataObjects(statement);
    }
    insertVersion(connection, VERSION, now);
  }

  static void migrateV0(Connection connection, Instant now) throws SQLException {
    try (var statement = connection.createStatement()) {
      createDataObjects(statement);
    }
    try (var update =
        connection.prepareStatement(
            "UPDATE channel_schema SET version = ?, updated_at = ?"
                + " WHERE singleton = 1 AND version = 0")) {
      update.setInt(1, VERSION);
      update.setString(2, now.toString());
      if (update.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
    }
  }

  static void validateEmpty(Connection connection) throws SQLException {
    if (!userTables(connection).isEmpty()
        || !namedIndexes(connection).isEmpty()
        || hasViewsOrTriggers(connection)) {
      throw ChannelLedgerRepositoryException.schemaIncompatible();
    }
  }

  static void validateV0(Connection connection) throws SQLException {
    if (!userTables(connection).equals(Set.of("channel_schema"))
        || !namedIndexes(connection).isEmpty()
        || hasViewsOrTriggers(connection)) {
      throw ChannelLedgerRepositoryException.schemaIncompatible();
    }
    requireExactSql(connection, "table", "channel_schema", CREATE_SCHEMA);
    SchemaRecord record = readSchemaRecord(connection);
    if (record.version() != 0) {
      throw ChannelLedgerRepositoryException.schemaIncompatible();
    }
  }

  static void validateV1(Connection connection) throws SQLException {
    if (!userTables(connection).equals(TABLES)
        || !namedIndexes(connection).equals(INDEX_DEFINITIONS.keySet())
        || hasViewsOrTriggers(connection)) {
      throw ChannelLedgerRepositoryException.schemaIncompatible();
    }
    for (var definition : TABLE_DEFINITIONS.entrySet()) {
      requireExactSql(connection, "table", definition.getKey(), definition.getValue());
    }
    for (var definition : INDEX_DEFINITIONS.entrySet()) {
      requireExactSql(connection, "index", definition.getKey(), definition.getValue());
    }
    requireForeignKeys(connection);
    requireNoForeignKeyViolations(connection);
    SchemaRecord record = readSchemaRecord(connection);
    if (record.version() != VERSION) {
      throw ChannelLedgerRepositoryException.schemaIncompatible();
    }
  }

  static void validateHeader(Connection connection) throws SQLException {
    requireExactSql(connection, "table", "channel_schema", CREATE_SCHEMA);
  }

  static SchemaRecord readSchemaRecord(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT singleton, version, updated_at,"
                    + " typeof(singleton) AS singleton_type,"
                    + " typeof(version) AS version_type,"
                    + " typeof(updated_at) AS updated_at_type"
                    + " FROM channel_schema ORDER BY singleton")) {
      if (!rows.next()
          || !"integer".equals(rows.getString("singleton_type"))
          || rows.getLong("singleton") != 1L
          || rows.wasNull()) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
      if (!"integer".equals(rows.getString("version_type"))) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
      long version = rows.getLong("version");
      if (rows.wasNull()) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
      if (!"text".equals(rows.getString("updated_at_type"))) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
      String updatedAt = rows.getString("updated_at");
      if (rows.next()) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
      try {
        Instant instant = Instant.parse(updatedAt);
        if (!instant.toString().equals(updatedAt)) {
          throw ChannelLedgerRepositoryException.schemaIncompatible();
        }
        return new SchemaRecord(version, instant);
      } catch (DateTimeParseException | NullPointerException exception) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
    }
  }

  static Set<String> userTables(Connection connection) throws SQLException {
    return schemaNames(
        connection,
        "SELECT name FROM sqlite_schema" + " WHERE type = 'table' AND name NOT LIKE 'sqlite_%'");
  }

  private static Set<String> namedIndexes(Connection connection) throws SQLException {
    return schemaNames(
        connection,
        "SELECT name FROM sqlite_schema" + " WHERE type = 'index' AND name NOT LIKE 'sqlite_%'");
  }

  private static Set<String> schemaNames(Connection connection, String sql) throws SQLException {
    var names = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        names.add(rows.getString(1));
      }
    }
    return Set.copyOf(names);
  }

  private static boolean hasViewsOrTriggers(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT 1 FROM sqlite_schema WHERE type IN ('view', 'trigger') LIMIT 1")) {
      return rows.next();
    }
  }

  private static void createDataObjects(java.sql.Statement statement) throws SQLException {
    statement.executeUpdate(CREATE_CURSORS);
    statement.executeUpdate(CREATE_CLAIMS);
    statement.executeUpdate(CREATE_EVENTS);
    statement.executeUpdate(CREATE_DELIVERIES);
    statement.executeUpdate(CREATE_PARTS);
    statement.executeUpdate(CREATE_ATTEMPTS);
    statement.executeUpdate(CREATE_INBOX_TURN_INDEX);
    statement.executeUpdate(CREATE_CLAIMS_RECOVERY_INDEX);
    statement.executeUpdate(CREATE_DELIVERIES_DUE_INDEX);
    statement.executeUpdate(CREATE_PARTS_DUE_INDEX);
    statement.executeUpdate(CREATE_ATTEMPTS_OUTCOME_INDEX);
  }

  private static void insertVersion(Connection connection, int version, Instant now)
      throws SQLException {
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO channel_schema (singleton, version, updated_at) VALUES (1, ?, ?)")) {
      insert.setInt(1, version);
      insert.setString(2, now.toString());
      if (insert.executeUpdate() != 1) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
    }
  }

  private static void requireExactSql(
      Connection connection, String expectedType, String name, String expectedSql)
      throws SQLException {
    try (var query =
        connection.prepareStatement("SELECT type, sql FROM sqlite_schema WHERE name = ?")) {
      query.setString(1, name);
      try (var rows = query.executeQuery()) {
        if (!rows.next()
            || !expectedType.equals(rows.getString("type"))
            || !normalizeSql(expectedSql).equals(normalizeSql(rows.getString("sql")))
            || rows.next()) {
          throw ChannelLedgerRepositoryException.schemaIncompatible();
        }
      }
    }
  }

  private static String normalizeSql(String sql) {
    return sql == null ? "" : sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }

  private static void requireForeignKeys(Connection connection) throws SQLException {
    for (String table : TABLES) {
      List<ForeignKeySpec> expected = FOREIGN_KEYS.getOrDefault(table, List.of());
      var actual = new ArrayList<ForeignKeySpec>();
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "PRAGMA foreign_key_list('" + table.replace("'", "''") + "')")) {
        while (rows.next()) {
          actual.add(
              new ForeignKeySpec(
                  rows.getInt("id"),
                  rows.getInt("seq"),
                  rows.getString("table"),
                  rows.getString("from"),
                  rows.getString("to"),
                  rows.getString("on_update"),
                  rows.getString("on_delete")));
        }
      }
      actual.sort(
          java.util.Comparator.comparingInt(ForeignKeySpec::id)
              .thenComparingInt(ForeignKeySpec::sequence));
      if (!actual.equals(expected)) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
    }
  }

  private static void requireNoForeignKeyViolations(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
      if (rows.next()) {
        throw ChannelLedgerRepositoryException.schemaIncompatible();
      }
    }
  }

  record SchemaRecord(long version, Instant updatedAt) {}

  private record ForeignKeySpec(
      int id,
      int sequence,
      String targetTable,
      String fromColumn,
      String targetColumn,
      String onUpdate,
      String onDelete) {}
}
