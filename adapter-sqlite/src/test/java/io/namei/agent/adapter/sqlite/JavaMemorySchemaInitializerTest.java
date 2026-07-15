package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaMemorySchemaInitializerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-15T05:00:00Z"), ZoneOffset.UTC);

  @TempDir Path tempDir;

  @Test
  void createsTheExactV1SchemaWithoutReadingOrChangingMemory2() throws Exception {
    Path database = database();
    Path legacy = database.getParent().resolve("memory2.db");
    Files.createDirectories(legacy.getParent());
    Files.writeString(legacy, "legacy-must-stay-untouched");
    var initializer = new JavaMemorySchemaInitializer(database, 5_000);

    initializer.initialize();

    assertThat(database).isRegularFile();
    assertThat(Files.readString(legacy)).isEqualTo("legacy-must-stay-untouched");
    try (var connection = initializer.openConnection()) {
      assertThat(nonInternalTables(connection))
          .containsExactlyInAnyOrder("memory_schema", "memory_items", "memory_mutations");
      assertThat(columns(connection, "memory_schema"))
          .containsExactly("singleton", "version", "updated_at");
      assertThat(columns(connection, "memory_items"))
          .containsExactly(
              "id",
              "scope_binding",
              "memory_type",
              "content",
              "content_hash",
              "embedding",
              "embedding_model",
              "embedding_dimensions",
              "reinforcement",
              "emotional_weight",
              "source_kind",
              "happened_at",
              "revision",
              "created_at",
              "updated_at");
      assertThat(columns(connection, "memory_mutations"))
          .containsExactly(
              "id",
              "scope_binding",
              "request_id",
              "operation",
              "argument_hash",
              "item_id",
              "result_status",
              "created_at");
      assertThat(schemaVersion(connection)).isEqualTo(1);
      assertThat(pragmaInt(connection, "busy_timeout")).isEqualTo(5_000);
      assertThat(indexColumns(connection, "ix_memory_items_scope_updated"))
          .containsExactly("scope_binding ASC", "updated_at DESC", "id ASC");
      assertThat(uniqueIndexes(connection, "memory_items"))
          .contains(List.of("scope_binding", "memory_type", "content_hash"));
      assertThat(uniqueIndexes(connection, "memory_mutations"))
          .contains(List.of("scope_binding", "request_id"));
    }
    assertThatThrownBy(
            () ->
                new JavaMemorySchemaInitializer(database.getParent().resolve("memory2.db"), 5_000))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void repeatedInitializationPreservesSchemaTimestampAndData() throws Exception {
    var initializer = new JavaMemorySchemaInitializer(database(), 5_000);
    initializer.initialize();
    try (var connection = initializer.openConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO memory_items (
            id, scope_binding, memory_type, content, content_hash, embedding,
            embedding_model, embedding_dimensions, source_kind, created_at, updated_at
          ) VALUES (
            'memory-0001', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
            'NOTE', 'keep-me', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
            x'0000803f', 'model', 1, 'EXPLICIT_API',
            '2026-07-15T05:00:00Z', '2026-07-15T05:00:00Z'
          )
          """);
    }
    String schemaTimestamp = schemaTimestamp(initializer);

    initializer.initialize();

    assertThat(schemaTimestamp(initializer)).isEqualTo(schemaTimestamp);
    try (var connection = initializer.openConnection();
        var rows = connection.createStatement().executeQuery("SELECT content FROM memory_items")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("keep-me");
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void rejectsFutureVersionsWithoutChangingThem() throws Exception {
    var initializer = new JavaMemorySchemaInitializer(database(), 5_000);
    initializer.initialize();
    try (var connection = initializer.openConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE memory_schema SET version = 2");
    }

    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .extracting(exception -> ((JavaMemoryRepositoryException) exception).failure())
        .isEqualTo(JavaMemoryRepositoryFailure.SCHEMA_INCOMPATIBLE);

    try (var connection = initializer.openConnection()) {
      assertThat(schemaVersion(connection)).isEqualTo(2);
      assertThat(nonInternalTables(connection))
          .containsExactlyInAnyOrder("memory_schema", "memory_items", "memory_mutations");
    }

    try (var connection = initializer.openConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE memory_schema SET version = 4294967297");
    }
    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .extracting(exception -> ((JavaMemoryRepositoryException) exception).failure())
        .isEqualTo(JavaMemoryRepositoryFailure.SCHEMA_INCOMPATIBLE);
    try (var connection = initializer.openConnection();
        var rows = connection.createStatement().executeQuery("SELECT version FROM memory_schema")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong(1)).isEqualTo(4_294_967_297L);
    }
  }

  @Test
  void rejectsAnIncompatibleSameNamedTableBeforeCreatingAnythingElse() throws Exception {
    Path database = database();
    Files.createDirectories(database.getParent());
    try (var connection = rawConnection(database);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE memory_schema (singleton INTEGER PRIMARY KEY, version INTEGER)");
      statement.execute("INSERT INTO memory_schema VALUES (1, 1)");
    }

    assertThatThrownBy(() -> new JavaMemorySchemaInitializer(database, 5_000).initialize())
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .hasMessage("Memory Schema 不兼容");

    try (var connection = rawConnection(database)) {
      assertThat(nonInternalTables(connection)).containsExactly("memory_schema");
      assertThat(columns(connection, "memory_schema")).containsExactly("singleton", "version");
    }
  }

  @Test
  void rejectsCorruptDatabasesWithAStableErrorAndNoRewrite() throws Exception {
    Path database = database();
    Files.createDirectories(database.getParent());
    byte[] corrupt = "not-a-sqlite-database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(database, corrupt);

    assertThatThrownBy(() -> new JavaMemorySchemaInitializer(database, 5_000).initialize())
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .hasMessage("Java Memory 数据库不可用")
        .extracting(exception -> ((JavaMemoryRepositoryException) exception).failure())
        .isEqualTo(JavaMemoryRepositoryFailure.DATABASE_UNAVAILABLE);
    assertThat(Files.readAllBytes(database)).containsExactly(corrupt);
  }

  @Test
  void backsUpACompatibleV0DatabaseBeforeUpgradingToV1() throws Exception {
    Path database = database();
    createV0(database);
    var initializer = new JavaMemorySchemaInitializer(database, 5_000);

    initializer.initialize();

    try (var connection = initializer.openConnection()) {
      assertThat(schemaVersion(connection)).isEqualTo(1);
      assertThat(nonInternalTables(connection))
          .containsExactlyInAnyOrder("memory_schema", "memory_items", "memory_mutations");
    }
    Path backup = onlyBackup(database);
    try (var connection = rawConnection(backup)) {
      assertThat(schemaVersion(connection)).isZero();
      assertThat(nonInternalTables(connection)).containsExactly("memory_schema");
    }
  }

  @Test
  void backupFailureLeavesV0WithoutAnyDdlOrDmlChanges() throws Exception {
    Path database = database();
    createV0(database);
    var initializer =
        new JavaMemorySchemaInitializer(
            database,
            5_000,
            CLOCK,
            (connection, destination) -> {
              throw new SQLException("sensitive-provider-message");
            });

    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .hasMessage("Memory Schema 备份失败")
        .hasMessageNotContaining("sensitive-provider-message")
        .extracting(exception -> ((JavaMemoryRepositoryException) exception).failure())
        .isEqualTo(JavaMemoryRepositoryFailure.BACKUP_FAILED);

    try (var connection = rawConnection(database)) {
      assertThat(schemaVersion(connection)).isZero();
      assertThat(nonInternalTables(connection)).containsExactly("memory_schema");
      assertThat(schemaTimestamp(connection)).isEqualTo("2026-07-15T04:00:00Z");
    }
    try (var files = Files.list(database.getParent())) {
      assertThat(files.filter(path -> path.getFileName().toString().endsWith(".bak"))).isEmpty();
    }
  }

  private Path database() {
    return tempDir.resolve("workspace/memory/agent-memory.db");
  }

  private static void createV0(Path database) throws Exception {
    Files.createDirectories(database.getParent());
    try (var connection = rawConnection(database);
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE memory_schema (
            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
            version INTEGER NOT NULL,
            updated_at TEXT NOT NULL
          )
          """);
      statement.execute("INSERT INTO memory_schema VALUES (1, 0, '2026-07-15T04:00:00Z')");
    }
  }

  private static Connection rawConnection(Path database) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
  }

  private static Set<String> nonInternalTables(Connection connection) throws Exception {
    var names = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE"
                    + " 'sqlite_%'")) {
      while (rows.next()) {
        names.add(rows.getString(1));
      }
    }
    return names;
  }

  private static List<String> columns(Connection connection, String table) throws Exception {
    var names = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
    }
    return names;
  }

  private static int schemaVersion(Connection connection) throws Exception {
    try (var rows =
        connection.createStatement().executeQuery("SELECT version FROM memory_schema")) {
      assertThat(rows.next()).isTrue();
      int version = rows.getInt(1);
      assertThat(rows.next()).isFalse();
      return version;
    }
  }

  private static String schemaTimestamp(JavaMemorySchemaInitializer initializer) throws Exception {
    try (var connection = initializer.openConnection()) {
      return schemaTimestamp(connection);
    }
  }

  private static String schemaTimestamp(Connection connection) throws Exception {
    try (var rows =
        connection.createStatement().executeQuery("SELECT updated_at FROM memory_schema")) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private static int pragmaInt(Connection connection, String name) throws Exception {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA " + name)) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private static List<String> indexColumns(Connection connection, String index) throws Exception {
    var columns = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA index_xinfo(" + index + ")")) {
      while (rows.next()) {
        if (rows.getInt("key") == 1 && rows.getInt("cid") >= 0) {
          columns.add(rows.getString("name") + (rows.getBoolean("desc") ? " DESC" : " ASC"));
        }
      }
    }
    return columns;
  }

  private static List<List<String>> uniqueIndexes(Connection connection, String table)
      throws Exception {
    var indexes = new ArrayList<List<String>>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
      while (rows.next()) {
        if (rows.getBoolean("unique")) {
          indexes.add(indexColumnsWithoutOrder(connection, rows.getString("name")));
        }
      }
    }
    return indexes;
  }

  private static List<String> indexColumnsWithoutOrder(Connection connection, String index)
      throws Exception {
    var columns = new ArrayList<String>();
    try (var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA index_info('" + index + "')")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    return List.copyOf(columns);
  }

  private static Path onlyBackup(Path database) throws Exception {
    try (var files = Files.list(database.getParent())) {
      List<Path> backups =
          files
              .filter(path -> path.getFileName().toString().startsWith("agent-memory.db.v0-to-v1-"))
              .filter(path -> path.getFileName().toString().endsWith(".bak"))
              .toList();
      assertThat(backups).hasSize(1);
      return backups.getFirst();
    }
  }
}
