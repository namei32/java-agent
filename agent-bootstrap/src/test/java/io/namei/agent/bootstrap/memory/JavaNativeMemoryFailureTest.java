package io.namei.agent.bootstrap.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.namei.agent.adapter.sqlite.Float32VectorCodec;
import io.namei.agent.adapter.sqlite.JavaMemorySchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcJavaMemoryStore;
import io.namei.agent.application.MemoryDeleteService;
import io.namei.agent.application.MemoryQueryService;
import io.namei.agent.application.MemoryWriteService;
import io.namei.agent.bootstrap.http.ApiExceptionHandler;
import io.namei.agent.bootstrap.http.MemoryController;
import io.namei.agent.bootstrap.http.MemoryManagementApi;
import io.namei.agent.kernel.error.EmbeddingInvocationException;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.port.EmbeddingPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Tag("failure")
class JavaNativeMemoryFailureTest {
  private static final Instant NOW = Instant.parse("2026-07-15T05:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final EmbeddingVector VECTOR = new EmbeddingVector(new float[] {1.0f, 0.0f});

  @TempDir Path temporaryDirectory;

  @Test
  void embeddingFailuresReturnStable502AndLeaveItemsAndLedgerEmpty() throws Exception {
    assertEmbeddingFailure(
        "provider-failure",
        request -> {
          throw new EmbeddingInvocationException(
              new IllegalStateException("Bearer provider-secret and private memory"));
        },
        "provider-secret");
    assertEmbeddingFailure(
        "invalid-response",
        request -> new EmbeddingResult("contract-embedding", 2, List.of()),
        "private memory");
  }

  @Test
  void conflictingRetryDoesNotEmbedGenerateAnIdOrMutateAgain() throws Exception {
    Harness harness = harness("idempotency", new CountingEmbedding(), new AtomicInteger());
    CountingEmbedding embedding = (CountingEmbedding) harness.embedding();
    AtomicInteger ids = harness.ids();

    harness
        .mvc()
        .perform(
            put("/api/v1/sessions/session-java-memory-001/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeBody("req-retry-001", "first content")))
        .andExpect(status().isCreated());

    harness
        .mvc()
        .perform(
            put("/api/v1/sessions/session-java-memory-001/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeBody("req-retry-001", "different content")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("记忆请求幂等冲突"))
        .andExpect(jsonPath("$.detail").value("记忆请求幂等冲突"))
        .andExpect(content().string(not(containsString("different content"))));

    assertThat(embedding.calls).isEqualTo(1);
    assertThat(ids.get()).isEqualTo(1);
    assertCounts(harness.schema(), 1, 1);
  }

  @Test
  void anUnavailableDatabaseReturnsStable500WithoutLeakingItsPath() throws Exception {
    Harness harness = harness("database-failure", new CountingEmbedding(), new AtomicInteger());
    Path database = harness.database();
    Files.delete(database);
    Files.createDirectory(database);

    harness
        .mvc()
        .perform(get("/api/v1/sessions/session-java-memory-001/memories"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.title").value("记忆持久化失败"))
        .andExpect(jsonPath("$.detail").value("记忆持久化失败"))
        .andExpect(content().string(not(containsString(database.toString()))));
  }

  private void assertEmbeddingFailure(
      String directory, EmbeddingPort embedding, String forbiddenResponseText) throws Exception {
    Harness harness = harness(directory, embedding, new AtomicInteger());

    harness
        .mvc()
        .perform(
            put("/api/v1/sessions/session-java-memory-001/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeBody("req-write-001", "private memory")))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("记忆 Embedding 失败"))
        .andExpect(jsonPath("$.detail").value("记忆 Embedding 失败"))
        .andExpect(content().string(not(containsString(forbiddenResponseText))));

    assertThat(harness.ids().get()).isZero();
    assertCounts(harness.schema(), 0, 0);
  }

  private Harness harness(String directory, EmbeddingPort embedding, AtomicInteger ids) {
    Path database = temporaryDirectory.resolve(directory).resolve("memory/agent-memory.db");
    var schema = new JavaMemorySchemaInitializer(database, 5_000);
    schema.initialize();
    var store = new JdbcJavaMemoryStore(schema, new Float32VectorCodec());
    var api =
        MemoryManagementApi.enabled(
            new MemoryWriteService(
                embedding,
                store,
                () -> "memory-" + String.format("%04d", ids.incrementAndGet()),
                CLOCK),
            new MemoryQueryService(store),
            new MemoryDeleteService(store, CLOCK));
    MockMvc mvc =
        standaloneSetup(new MemoryController(api))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    return new Harness(database, schema, embedding, ids, mvc);
  }

  private static void assertCounts(
      JavaMemorySchemaInitializer schema, long expectedItems, long expectedMutations)
      throws Exception {
    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM memory_items")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isEqualTo(expectedItems);
      }
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM memory_mutations")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isEqualTo(expectedMutations);
      }
    }
  }

  private static String writeBody(String requestId, String content) {
    return """
    {
      "requestId":"%s",
      "type":"NOTE",
      "content":"%s",
      "emotionalWeight":0
    }
    """
        .formatted(requestId, content);
  }

  private record Harness(
      Path database,
      JavaMemorySchemaInitializer schema,
      EmbeddingPort embedding,
      AtomicInteger ids,
      MockMvc mvc) {}

  private static final class CountingEmbedding implements EmbeddingPort {
    private int calls;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
      calls++;
      return new EmbeddingResult(
          "contract-embedding", 2, request.texts().stream().map(ignored -> VECTOR).toList());
    }
  }
}
