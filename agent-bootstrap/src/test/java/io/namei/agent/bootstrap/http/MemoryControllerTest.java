package io.namei.agent.bootstrap.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.namei.agent.application.MemoryDeleteOutcome;
import io.namei.agent.application.MemoryView;
import io.namei.agent.application.MemoryWriteOutcome;
import io.namei.agent.application.MemoryWriteRequest;
import io.namei.agent.kernel.error.EmbeddingInvocationException;
import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.memory.MemoryDeleteStatus;
import io.namei.agent.kernel.memory.MemoryIdempotencyConflictException;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.memory.MemoryWriteStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class MemoryControllerTest {
  private static final String SESSION = "session-java-memory-001";
  private static final Instant HAPPENED_AT = Instant.parse("2026-07-15T04:00:00Z");
  private static final Instant NOW = Instant.parse("2026-07-15T05:00:00Z");

  private final StubMemoryManagementApi api = new StubMemoryManagementApi();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    api.reset();
    mvc =
        standaloneSetup(new MemoryController(api))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestIdFilter(), new ContentLengthLimitFilter())
            .build();
  }

  @Test
  void createsAndReinforcesMemoryWithTheApprovedPublicShape() throws Exception {
    api.writeResult = new MemoryWriteOutcome(MemoryWriteStatus.CREATED, memory());

    performWrite()
        .andExpect(status().isCreated())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().exists(RequestIdFilter.HEADER))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.memory.id").value("memory-0001"))
        .andExpect(jsonPath("$.memory.type").value("PREFERENCE"))
        .andExpect(jsonPath("$.memory.content").value("回答时 先给结论"))
        .andExpect(jsonPath("$.memory.reinforcement").value(1))
        .andExpect(jsonPath("$.memory.emotionalWeight").value(2))
        .andExpect(jsonPath("$.memory.happenedAt").value(HAPPENED_AT.toString()))
        .andExpect(jsonPath("$.memory.createdAt").value(NOW.toString()))
        .andExpect(jsonPath("$.memory.updatedAt").value(NOW.toString()))
        .andExpect(jsonPath("$.memory.embedding").doesNotExist())
        .andExpect(jsonPath("$.memory.contentHash").doesNotExist())
        .andExpect(jsonPath("$.memory.scope").doesNotExist())
        .andExpect(jsonPath("$.requestId").doesNotExist());

    assertThat(api.sessionId).isEqualTo(SESSION);
    assertThat(api.writeRequest)
        .isEqualTo(
            new MemoryWriteRequest(
                "req-write-001", MemoryType.PREFERENCE, "回答时 先给结论", 2, HAPPENED_AT));

    api.writeResult = new MemoryWriteOutcome(MemoryWriteStatus.REINFORCED, memory());
    performWrite().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REINFORCED"));
  }

  @Test
  void listsOnlyPublicMemoryFieldsInApplicationOrder() throws Exception {
    var recent =
        new MemoryView(
            "memory-0002",
            MemoryType.NOTE,
            "Java Agent 使用原生记忆库",
            1,
            0,
            null,
            NOW.plusSeconds(1800),
            NOW.plusSeconds(3600));
    api.memories = List.of(recent, memory());

    mvc.perform(get("/api/v1/sessions/{sessionId}/memories", SESSION))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memories.length()").value(2))
        .andExpect(jsonPath("$.memories[0].id").value("memory-0002"))
        .andExpect(jsonPath("$.memories[0].happenedAt").isEmpty())
        .andExpect(jsonPath("$.memories[1].id").value("memory-0001"))
        .andExpect(jsonPath("$.memories[0].embedding").doesNotExist())
        .andExpect(jsonPath("$.memories[0].embeddingModel").doesNotExist())
        .andExpect(jsonPath("$.memories[0].contentHash").doesNotExist())
        .andExpect(jsonPath("$.memories[0].scope").doesNotExist());

    assertThat(api.sessionId).isEqualTo(SESSION);
  }

  @Test
  void deletesWithTheRequiredIdempotencyHeaderAndMapsNotFoundUniformly() throws Exception {
    api.deleteResult = new MemoryDeleteOutcome(MemoryDeleteStatus.DELETED, "memory-0001");

    mvc.perform(
            delete("/api/v1/sessions/{sessionId}/memories/{memoryId}", SESSION, "memory-0001")
                .header("Idempotency-Key", "req-delete-001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DELETED"))
        .andExpect(jsonPath("$.id").value("memory-0001"));

    assertThat(api.sessionId).isEqualTo(SESSION);
    assertThat(api.requestId).isEqualTo("req-delete-001");
    assertThat(api.memoryId).isEqualTo("memory-0001");

    api.deleteResult = new MemoryDeleteOutcome(MemoryDeleteStatus.NOT_FOUND, "memory-0001");
    mvc.perform(
            delete(
                    "/api/v1/sessions/{sessionId}/memories/{memoryId}",
                    "another-session",
                    "memory-0001")
                .header("Idempotency-Key", "req-delete-002"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("记忆不存在"))
        .andExpect(jsonPath("$.detail").value("记忆不存在"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("scope_binding"))));
  }

  @Test
  void rejectsInvalidFieldsMalformedJsonAndMissingDeleteHeaderBeforeTheApi() throws Exception {
    mvc.perform(
            put("/api/v1/sessions/{sessionId}/memories", "bad.session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validWriteBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("请求参数无效"));

    mvc.perform(
            put("/api/v1/sessions/{sessionId}/memories", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"requestId":"bad request","type":"PREFERENCE","content":"content","emotionalWeight":2}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("请求参数无效"));

    mvc.perform(
            put("/api/v1/sessions/{sessionId}/memories", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"requestId":"req","type":null,"content":"content","emotionalWeight":2}
                    """))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/sessions/{sessionId}/memories", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"requestId":"req","type":"NOTE","content":" ","emotionalWeight":11}
                    """))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/sessions/{sessionId}/memories", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"req\",\"type\":\"NOTE\",\"content\":\""
                        + "x".repeat(4001)
                        + "\",\"emotionalWeight\":0}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/sessions/{sessionId}/memories", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("JSON 格式无效"));

    mvc.perform(
            put("/api/v1/sessions/{sessionId}/memories", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requestId":"req",
                      "type":"NOTE",
                      "content":"content",
                      "emotionalWeight":0,
                      "embedding":[1.0, 0.0]
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("JSON 格式无效"));

    mvc.perform(delete("/api/v1/sessions/{sessionId}/memories/{memoryId}", SESSION, "memory-0001"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("请求参数无效"));

    assertThat(api.calls).isZero();
  }

  @Test
  void rejectsAnOversizedRequestBodyWithoutCallingTheApi() throws Exception {
    String body =
        "{\"requestId\":\"req\",\"type\":\"NOTE\",\"content\":\""
            + "x".repeat(65_536)
            + "\",\"emotionalWeight\":0}";

    mvc.perform(
            put("/api/v1/sessions/{sessionId}/memories", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.title").value("请求体过大"));

    assertThat(api.calls).isZero();
  }

  @Test
  void returnsAStableUnavailableProblemWhenMemoryApiIsDisabled() throws Exception {
    api.failure = new MemoryApiUnavailableException();

    mvc.perform(get("/api/v1/sessions/{sessionId}/memories", SESSION))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("记忆功能不可用"))
        .andExpect(jsonPath("$.detail").value("记忆功能不可用"));
  }

  @Test
  @Tag("failure")
  void mapsMemoryFailuresWithoutLeakingProviderDatabaseOrRequestData() throws Exception {
    api.failure = new MemoryIdempotencyConflictException();
    performWrite()
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("记忆请求幂等冲突"));

    api.failure =
        new EmbeddingInvocationException(
            new IllegalStateException("Bearer provider-secret and private content"));
    performWrite()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("记忆 Embedding 失败"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("provider-secret"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private content"))));

    api.failure = new InvalidEmbeddingResponseException();
    performWrite()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("记忆 Embedding 失败"));

    api.failure =
        new MemoryApiPersistenceException(
            new IllegalStateException("/private/workspace/agent-memory.db SQL secret"));
    performWrite()
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.title").value("记忆持久化失败"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/private/workspace"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL secret"))));
  }

  private org.springframework.test.web.servlet.ResultActions performWrite() throws Exception {
    return mvc.perform(
        put("/api/v1/sessions/{sessionId}/memories", SESSION)
            .contentType(MediaType.APPLICATION_JSON)
            .content(validWriteBody()));
  }

  private static String validWriteBody() {
    return """
    {
      "requestId":"req-write-001",
      "type":"PREFERENCE",
      "content":"  回答时\\t先给结论  ",
      "emotionalWeight":2,
      "happenedAt":"2026-07-15T04:00:00Z"
    }
    """;
  }

  private static MemoryView memory() {
    return new MemoryView(
        "memory-0001", MemoryType.PREFERENCE, "回答时 先给结论", 1, 2, HAPPENED_AT, NOW, NOW);
  }

  private static final class StubMemoryManagementApi implements MemoryManagementApi {
    private MemoryWriteOutcome writeResult;
    private List<MemoryView> memories;
    private MemoryDeleteOutcome deleteResult;
    private RuntimeException failure;
    private int calls;
    private String sessionId;
    private String requestId;
    private String memoryId;
    private MemoryWriteRequest writeRequest;

    private void reset() {
      writeResult = new MemoryWriteOutcome(MemoryWriteStatus.CREATED, memory());
      memories = List.of();
      deleteResult = new MemoryDeleteOutcome(MemoryDeleteStatus.DELETED, "memory-0001");
      failure = null;
      calls = 0;
      sessionId = null;
      requestId = null;
      memoryId = null;
      writeRequest = null;
    }

    @Override
    public MemoryWriteOutcome write(String sessionId, MemoryWriteRequest request) {
      called(sessionId);
      writeRequest = request;
      return writeResult;
    }

    @Override
    public List<MemoryView> list(String sessionId) {
      called(sessionId);
      return memories;
    }

    @Override
    public MemoryDeleteOutcome delete(String sessionId, String requestId, String memoryId) {
      called(sessionId);
      this.requestId = requestId;
      this.memoryId = memoryId;
      return deleteResult;
    }

    private void called(String sessionId) {
      calls++;
      this.sessionId = sessionId;
      if (failure != null) {
        throw failure;
      }
    }
  }
}
