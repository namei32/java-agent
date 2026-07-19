package io.namei.agent.bootstrap.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.namei.agent.adapter.sqlite.Float32VectorCodec;
import io.namei.agent.adapter.sqlite.JavaMemorySchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcJavaMemoryStore;
import io.namei.agent.application.MemoryDeleteService;
import io.namei.agent.application.MemoryInjection;
import io.namei.agent.application.MemoryInjectionFormatter;
import io.namei.agent.application.MemoryQueryService;
import io.namei.agent.application.MemoryWriteRequest;
import io.namei.agent.application.MemoryWriteService;
import io.namei.agent.application.SemanticMemorySearch;
import io.namei.agent.bootstrap.http.ApiExceptionHandler;
import io.namei.agent.bootstrap.http.MemoryController;
import io.namei.agent.bootstrap.http.MemoryManagementApi;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryStorePort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class JavaNativeMemoryContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant WRITE_TIME = Instant.parse("2026-07-15T05:00:00Z");
  private static final Instant SECOND_WRITE_TIME = Instant.parse("2026-07-15T05:30:00Z");
  private static final Instant SECOND_UPDATE_TIME = Instant.parse("2026-07-15T06:00:00Z");
  private static final String EMBEDDING_MODEL = "contract-embedding";
  private static final EmbeddingVector EMBEDDING = vector(1.0, 0.0);

  @TempDir Path temporaryDirectory;

  @Test
  void initializesTheApprovedSchemaAndEncodesTheApprovedFloat32Vector() throws Exception {
    JsonNode fixture = fixture();
    assertFixtureInventory(fixture);

    JsonNode schemaCase = caseById(fixture, "schema-v2");
    JsonNode schemaExpected = schemaCase.path("expected");
    Path database =
        temporaryDirectory.resolve(schemaExpected.path("databaseRelativePath").asString());
    var schema = new JavaMemorySchemaInitializer(database, 5_000);
    schema.initialize();

    assertThat(temporaryDirectory.relativize(database).toString().replace('\\', '/'))
        .isEqualTo(schemaExpected.path("databaseRelativePath").asString());
    try (var connection = schema.openConnection()) {
      assertSchema(connection, schemaExpected);
    }

    JsonNode codecCase = caseById(fixture, "float32-little-endian");
    JsonNode codecExpected = codecCase.path("expected");
    var values = new float[codecCase.path("input").path("vector").size()];
    for (int index = 0; index < values.length; index++) {
      values[index] = (float) codecCase.path("input").path("vector").get(index).asDouble();
    }
    var codec = new Float32VectorCodec();
    byte[] encoded = codec.encode(new EmbeddingVector(values));

    assertThat(codecExpected.path("format").asString()).isEqualTo("IEEE_754_FLOAT32");
    assertThat(codecExpected.path("byteOrder").asString()).isEqualTo("LITTLE_ENDIAN");
    assertThat(encoded).hasSize(codecExpected.path("byteLength").asInt());
    assertThat(values).hasSize(codecExpected.path("dimensions").asInt());
    assertThat(HexFormat.of().formatHex(encoded)).isEqualTo(codecExpected.path("hex").asString());
    assertThat(codec.decode(encoded, values.length).values()).containsExactly(values);
    assertThat(codecExpected.path("roundTrip").size()).isEqualTo(values.length);
    for (int index = 0; index < values.length; index++) {
      assertThat((double) values[index])
          .isEqualTo(codecExpected.path("roundTrip").get(index).asDouble());
    }
  }

  @Test
  void executesApprovedWriteListAndDeleteContractsThroughProductionLayers() throws Exception {
    JsonNode fixture = fixture();
    JsonNode schemaCase = caseById(fixture, "schema-v2");
    Path database =
        temporaryDirectory.resolve(
            schemaCase.path("expected").path("databaseRelativePath").asString());
    var schema = new JavaMemorySchemaInitializer(database, 5_000);
    schema.initialize();
    var store = new JdbcJavaMemoryStore(schema, new Float32VectorCodec());
    var embeddings = new ContractEmbedding();
    var writes =
        new MemoryWriteService(embeddings, store, () -> "memory-0001", fixedClock(WRITE_TIME));
    var api =
        MemoryManagementApi.enabled(
            writes,
            new MemoryQueryService(store),
            new MemoryDeleteService(store, fixedClock(WRITE_TIME)));
    MockMvc mvc =
        standaloneSetup(new MemoryController(api))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    JsonNode writeCase = caseById(fixture, "http-write-created");
    JsonNode writeRequest = writeCase.path("input").path("request");
    assertThat(writeRequest.path("method").asString()).isEqualTo("PUT");
    JsonNode writeResponse =
        perform(
            mvc,
            put(writeRequest.path("path").asString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeRequest.path("body").toString()),
            writeCase.path("expected").path("httpStatus").asInt());
    assertThat(writeResponse).isEqualTo(writeCase.path("expected").path("body"));

    assertStoredHashesAndCanonicalFields(schema, fixture);

    new MemoryWriteService(embeddings, store, () -> "memory-0002", fixedClock(SECOND_WRITE_TIME))
        .write(
            "session-java-memory-001",
            new MemoryWriteRequest(
                "req-write-002", MemoryType.NOTE, "Java Agent 使用原生记忆库", 0, null));
    try (var connection = schema.openConnection();
        var update =
            connection.prepareStatement("UPDATE memory_items SET updated_at = ? WHERE id = ?")) {
      update.setString(1, SECOND_UPDATE_TIME.toString());
      update.setString(2, "memory-0002");
      assertThat(update.executeUpdate()).isEqualTo(1);
    }

    JsonNode listCase = caseById(fixture, "http-list-public-fields");
    JsonNode listRequest = listCase.path("input").path("request");
    assertThat(listRequest.path("method").asString()).isEqualTo("GET");
    JsonNode listResponse =
        perform(
            mvc,
            get(listRequest.path("path").asString()),
            listCase.path("expected").path("httpStatus").asInt());
    assertThat(listResponse).isEqualTo(listCase.path("expected").path("body"));

    JsonNode deleteCase = caseById(fixture, "http-delete");
    JsonNode deleteRequest = deleteCase.path("input").path("request");
    assertThat(deleteRequest.path("method").asString()).isEqualTo("DELETE");
    JsonNode deleteResponse =
        perform(
            mvc,
            delete(deleteRequest.path("path").asString())
                .header(
                    "Idempotency-Key",
                    deleteRequest.path("headers").path("Idempotency-Key").asString()),
            deleteCase.path("expected").path("httpStatus").asInt());
    assertThat(deleteResponse).isEqualTo(deleteCase.path("expected").path("body"));

    JsonNode deleteHash = caseById(fixture, "delete-argument-hash-v1");
    assertCanonicalHash(deleteHash);
    try (var connection = schema.openConnection()) {
      assertThat(
              singleString(
                  connection,
                  "SELECT argument_hash FROM memory_mutations WHERE request_id = 'req-delete-001'"))
          .isEqualTo(deleteHash.path("expected").path("argumentHash").asString());
      assertThat(
              singleLong(connection, "SELECT COUNT(*) FROM memory_items WHERE id = 'memory-0001'"))
          .isZero();
      assertThat(singleLong(connection, "SELECT COUNT(*) FROM memory_items")).isEqualTo(1L);
    }
    assertThat(embeddings.calls).isEqualTo(2);
  }

  @Test
  void ranksTheApprovedCandidatesWithHotnessThresholdAndStableOrder() throws Exception {
    JsonNode testCase = caseById(fixture(), "hotness-and-stable-order");
    JsonNode input = testCase.path("input");
    var scope = new MemoryScope("a".repeat(64));
    var items = new ArrayList<MemoryItem>();
    for (JsonNode candidate : input.path("candidates")) {
      double semantic = candidate.path("semantic").asDouble();
      items.add(
          item(
              candidate.path("id").asString(),
              scope,
              MemoryType.NOTE,
              "content-" + candidate.path("id").asString(),
              vector(semantic, Math.sqrt(1.0 - semantic * semantic)),
              candidate.path("reinforcement").asInt(),
              candidate.path("emotionalWeight").asInt(),
              null,
              Instant.parse(candidate.path("updatedAt").asString())));
    }
    var request =
        new MemorySearchRequest(
            scope,
            EMBEDDING,
            EMBEDDING_MODEL,
            input.path("topK").asInt(),
            input.path("scoreThreshold").asDouble(),
            input.path("alpha").asDouble(),
            input.path("halfLifeDays").asDouble(),
            items.size(),
            Instant.parse(input.path("referenceTime").asString()));

    List<MemorySearchHit> actual =
        new SemanticMemorySearch(new FixtureStore(items)).search(request);
    JsonNode expected = testCase.path("expected");
    assertThat(actual)
        .extracting(hit -> hit.item().id())
        .containsExactlyElementsOf(strings(expected.path("ordered"), "id"));
    assertThat(actual)
        .extracting(hit -> hit.item().id())
        .doesNotContainAnyElementsOf(strings(expected.path("excludedIds"), null));
    for (int index = 0; index < actual.size(); index++) {
      JsonNode expectedHit = expected.path("ordered").get(index);
      assertThat(actual.get(index).semanticScore())
          .isCloseTo(expectedHit.path("semantic").asDouble(), offset(1.0e-7));
      assertThat(actual.get(index).hotnessScore())
          .isCloseTo(expectedHit.path("hotness").asDouble(), offset(1.0e-7));
      assertThat(actual.get(index).finalScore())
          .isCloseTo(expectedHit.path("final").asDouble(), offset(1.0e-7));
    }
  }

  @Test
  void formatsTheApprovedTwoSectionContextInjection() throws Exception {
    JsonNode testCase = caseById(fixture(), "two-section-injection");
    JsonNode input = testCase.path("input");
    var scope = new MemoryScope("a".repeat(64));
    var hits = new ArrayList<MemorySearchHit>();
    for (JsonNode configured : input.path("hits")) {
      Instant happenedAt =
          configured.path("happenedAt").isNull()
              ? null
              : Instant.parse(configured.path("happenedAt").asString());
      MemoryItem item =
          item(
              configured.path("id").asString(),
              scope,
              MemoryType.valueOf(configured.path("type").asString()),
              configured.path("content").asString(),
              EMBEDDING,
              1,
              0,
              happenedAt,
              WRITE_TIME);
      hits.add(new MemorySearchHit(item, 1.0, 0.5, 0.9));
    }

    MemoryInjection actual =
        new MemoryInjectionFormatter()
            .format(
                hits,
                input.path("maxRules").asInt(),
                input.path("maxRelated").asInt(),
                input.path("maxCharacters").asInt());
    JsonNode expected = testCase.path("expected");
    assertThat(actual.block()).isEqualTo(expected.path("injectedText").asString());
    assertThat(actual.rulesCount()).isEqualTo(expected.path("rulesCount").asInt());
    assertThat(actual.relatedCount()).isEqualTo(expected.path("relatedCount").asInt());
  }

  private static void assertFixtureInventory(JsonNode fixture) {
    assertThat(fixture.path("suite").asString()).isEqualTo("java-native-memory");
    assertThat(strings(fixture.path("cases"), "id"))
        .containsExactly(
            "schema-v2",
            "float32-little-endian",
            "scope-and-content-hash",
            "write-argument-hash-v1",
            "delete-argument-hash-v1",
            "http-write-created",
            "http-list-public-fields",
            "http-delete",
            "hotness-and-stable-order",
            "two-section-injection");
  }

  private static void assertSchema(Connection connection, JsonNode expected) throws SQLException {
    assertThat(singleLong(connection, "SELECT version FROM memory_schema WHERE singleton = 1"))
        .isEqualTo(expected.path("schemaVersion").asLong());
    assertThat(
            Instant.parse(
                singleString(
                    connection, "SELECT updated_at FROM memory_schema WHERE singleton = 1")))
        .isNotNull();

    Set<String> actualTables = new LinkedHashSet<>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
                    + " ORDER BY name")) {
      while (rows.next()) {
        actualTables.add(rows.getString(1));
      }
    }
    assertThat(actualTables)
        .containsExactlyInAnyOrderElementsOf(strings(expected.path("tables"), "name"));

    for (JsonNode table : expected.path("tables")) {
      String tableName = table.path("name").asString();
      String sql = schemaSql(connection, tableName);
      Map<String, ColumnMetadata> columns = columns(connection, tableName);
      assertThat(columns.keySet())
          .containsExactlyElementsOf(strings(table.path("columns"), "name"));
      for (JsonNode expectedColumn : table.path("columns")) {
        ColumnMetadata actual = columns.get(expectedColumn.path("name").asString());
        assertThat(actual.type()).isEqualTo(expectedColumn.path("type").asString());
        assertThat(actual.nullable()).isEqualTo(expectedColumn.path("nullable").asBoolean());
        assertThat(actual.primaryKey())
            .isEqualTo(expectedColumn.path("primaryKey").asBoolean(false));
        if (expectedColumn.has("default")) {
          assertThat(actual.defaultValue()).isEqualTo(expectedColumn.path("default").asString());
        } else {
          assertThat(actual.defaultValue()).isNull();
        }
        if (expectedColumn.path("autoIncrement").asBoolean(false)) {
          assertThat(normalizeSql(sql))
              .contains(
                  normalizeSql(
                      expectedColumn.path("name").asString()
                          + " INTEGER PRIMARY KEY AUTOINCREMENT"));
        }
      }
      for (JsonNode check : table.path("checks")) {
        assertThat(normalizeSql(sql)).contains(normalizeSql(check.asString()));
      }
      List<List<String>> expectedUnique = new ArrayList<>();
      for (JsonNode constraint : table.path("uniqueConstraints")) {
        expectedUnique.add(strings(constraint, null));
      }
      assertThat(uniqueConstraints(connection, tableName))
          .containsExactlyInAnyOrderElementsOf(expectedUnique);
      if (table.has("rows")) {
        JsonNode row = table.path("rows").get(0);
        assertThat(singleLong(connection, "SELECT singleton FROM memory_schema"))
            .isEqualTo(row.path("singleton").asLong());
        assertThat(singleLong(connection, "SELECT version FROM memory_schema"))
            .isEqualTo(row.path("version").asLong());
        assertThat(row.path("updated_at").asString()).isEqualTo("UTC_INSTANT");
      }
    }

    for (JsonNode expectedIndex : expected.path("indexes")) {
      String table = expectedIndex.path("table").asString();
      String index = expectedIndex.path("name").asString();
      Map<String, Boolean> indexes = indexes(connection, table);
      assertThat(indexes).containsKey(index);
      assertThat(indexes.get(index)).isEqualTo(expectedIndex.path("unique").asBoolean());
      assertThat(indexColumns(connection, index, true))
          .containsExactlyElementsOf(strings(expectedIndex.path("columns"), null));
    }
  }

  private static void assertStoredHashesAndCanonicalFields(
      JavaMemorySchemaInitializer schema, JsonNode fixture) throws Exception {
    JsonNode hashCase = caseById(fixture, "scope-and-content-hash");
    JsonNode hashExpected = hashCase.path("expected");
    assertThat(hashExpected.path("hashAlgorithm").asString()).isEqualTo("SHA-256");
    assertThat(hashExpected.path("hashEncoding").asString()).isEqualTo("lowercase-hex");
    assertThat(hashExpected.path("normalizedContent").asString()).isEqualTo("回答时 先给结论");

    JsonNode writeHash = caseById(fixture, "write-argument-hash-v1");
    assertCanonicalHash(writeHash);
    try (var connection = schema.openConnection()) {
      assertThat(
              singleString(
                  connection, "SELECT scope_binding FROM memory_items WHERE id = 'memory-0001'"))
          .isEqualTo(hashExpected.path("scopeBinding").asString());
      assertThat(
              singleString(
                  connection, "SELECT content_hash FROM memory_items WHERE id = 'memory-0001'"))
          .isEqualTo(hashExpected.path("contentHash").asString());
      assertThat(
              singleString(connection, "SELECT content FROM memory_items WHERE id = 'memory-0001'"))
          .isEqualTo(hashExpected.path("normalizedContent").asString());
      assertThat(
              singleString(
                  connection,
                  "SELECT argument_hash FROM memory_mutations WHERE request_id = 'req-write-001'"))
          .isEqualTo(writeHash.path("expected").path("argumentHash").asString());
    }
  }

  private static void assertCanonicalHash(JsonNode testCase) throws Exception {
    JsonNode expected = testCase.path("expected");
    assertThat(expected.path("hashAlgorithm").asString()).isEqualTo("SHA-256");
    assertThat(expected.path("fieldEncoding").asString())
        .isEqualTo("uint32-big-endian-utf8-byte-length");
    byte[] canonical = canonicalBytes(testCase.path("input").path("canonicalFields"));
    assertThat(HexFormat.of().formatHex(canonical))
        .isEqualTo(expected.path("canonicalHex").asString());
    assertThat(sha256(canonical)).isEqualTo(expected.path("argumentHash").asString());
  }

  private static byte[] canonicalBytes(JsonNode fields) {
    var encoded = new ArrayList<byte[]>();
    int length = 0;
    for (JsonNode field : fields) {
      byte[] value = field.asString().getBytes(StandardCharsets.UTF_8);
      encoded.add(value);
      length = Math.addExact(length, Math.addExact(Integer.BYTES, value.length));
    }
    var result = ByteBuffer.allocate(length);
    for (byte[] value : encoded) {
      result.putInt(value.length);
      result.put(value);
    }
    return result.array();
  }

  private static JsonNode perform(
      MockMvc mvc, MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
    String content =
        mvc.perform(request)
            .andExpect(status().is(expectedStatus))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JSON.readTree(content);
  }

  private static Map<String, ColumnMetadata> columns(Connection connection, String table)
      throws SQLException {
    var result = new LinkedHashMap<String, ColumnMetadata>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info('" + sqlLiteral(table) + "')")) {
      while (rows.next()) {
        boolean primaryKey = rows.getBoolean("pk");
        boolean nullable = !rows.getBoolean("notnull") && !primaryKey;
        result.put(
            rows.getString("name"),
            new ColumnMetadata(
                rows.getString("type").toUpperCase(Locale.ROOT),
                nullable,
                primaryKey,
                rows.getString("dflt_value")));
      }
    }
    return result;
  }

  private static List<List<String>> uniqueConstraints(Connection connection, String table)
      throws SQLException {
    var names = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA index_list('" + sqlLiteral(table) + "')")) {
      while (rows.next()) {
        if (rows.getBoolean("unique") && !"pk".equals(rows.getString("origin"))) {
          names.add(rows.getString("name"));
        }
      }
    }
    var result = new ArrayList<List<String>>();
    for (String name : names) {
      result.add(indexColumns(connection, name, false));
    }
    return result;
  }

  private static Map<String, Boolean> indexes(Connection connection, String table)
      throws SQLException {
    var result = new LinkedHashMap<String, Boolean>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA index_list('" + sqlLiteral(table) + "')")) {
      while (rows.next()) {
        result.put(rows.getString("name"), rows.getBoolean("unique"));
      }
    }
    return result;
  }

  private static List<String> indexColumns(
      Connection connection, String index, boolean includeOrder) throws SQLException {
    var result = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA index_xinfo('" + sqlLiteral(index) + "')")) {
      while (rows.next()) {
        if (rows.getInt("key") == 1 && rows.getInt("cid") >= 0) {
          String column = rows.getString("name");
          result.add(includeOrder ? column + (rows.getBoolean("desc") ? " DESC" : " ASC") : column);
        }
      }
    }
    return List.copyOf(result);
  }

  private static String schemaSql(Connection connection, String name) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name = ?")) {
      statement.setString(1, name);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static String singleString(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      String value = rows.getString(1);
      assertThat(rows.next()).isFalse();
      return value;
    }
  }

  private static long singleLong(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      long value = rows.getLong(1);
      assertThat(rows.next()).isFalse();
      return value;
    }
  }

  private static String normalizeSql(String value) {
    return value.toUpperCase(Locale.ROOT).replaceAll("\\s+", "").replace("(", "").replace(")", "");
  }

  private static String sqlLiteral(String value) {
    return value.replace("'", "''");
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static MemoryItem item(
      String id,
      MemoryScope scope,
      MemoryType type,
      String content,
      EmbeddingVector embedding,
      int reinforcement,
      int emotionalWeight,
      Instant happenedAt,
      Instant updatedAt) {
    return new MemoryItem(
        id,
        scope,
        type,
        content,
        sha256Unchecked(id),
        embedding,
        EMBEDDING_MODEL,
        reinforcement,
        emotionalWeight,
        MemorySourceKind.EXPLICIT_API,
        happenedAt,
        1,
        updatedAt.minusSeconds(1),
        updatedAt);
  }

  private static String sha256Unchecked(String value) {
    try {
      return sha256(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static EmbeddingVector vector(double... values) {
    var floats = new float[values.length];
    for (int index = 0; index < values.length; index++) {
      floats[index] = (float) values[index];
    }
    return new EmbeddingVector(floats);
  }

  private static Clock fixedClock(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  private static List<String> strings(JsonNode array, String field) {
    var result = new ArrayList<String>();
    for (JsonNode value : array) {
      result.add(field == null ? value.asString() : value.path(field).asString());
    }
    return List.copyOf(result);
  }

  private static JsonNode fixture() throws Exception {
    return JSON.readTree(goldenRoot().resolve("memory/java-native-memory.json"));
  }

  private static JsonNode caseById(JsonNode fixture, String id) {
    for (JsonNode testCase : fixture.path("cases")) {
      if (id.equals(testCase.path("id").asString())) {
        return testCase;
      }
    }
    throw new IllegalArgumentException("缺少 Java Memory Contract Case: " + id);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private record ColumnMetadata(
      String type, boolean nullable, boolean primaryKey, String defaultValue) {}

  private static final class ContractEmbedding implements EmbeddingPort {
    private int calls;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
      calls++;
      return new EmbeddingResult(
          EMBEDDING_MODEL,
          EMBEDDING.dimensions(),
          request.texts().stream().map(ignored -> EMBEDDING).toList());
    }
  }

  private static final class FixtureStore implements MemoryStorePort {
    private final List<MemoryItem> items;

    private FixtureStore(List<MemoryItem> items) {
      this.items = List.copyOf(items);
    }

    @Override
    public long candidateCount(MemoryScope scope) {
      return items.size();
    }

    @Override
    public List<MemoryItem> loadCandidates(MemorySearchRequest request) {
      return items;
    }

    @Override
    public List<MemoryItem> list(MemoryScope scope, int limit) {
      return items;
    }
  }
}
