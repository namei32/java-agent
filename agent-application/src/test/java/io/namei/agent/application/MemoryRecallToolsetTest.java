package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("failure")
class MemoryRecallToolsetTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void remainsDeferredAndProjectsOnlyMinimalCurrentScopeFields() throws Exception {
    ToolRegistry registry = registry();
    ToolCatalogSession session = registry.newCatalogSession();
    ToolInvocationContext context = ToolInvocationContext.none().withMemoryRecall(scope());

    assertThat(
            registry
                .execute(
                    new ToolCall("direct", "recall_memory", Map.of("query", "cache")),
                    TurnCancellation.none(),
                    session,
                    context)
                .content())
        .isEqualTo("工具不可用。");

    registry.execute(
        new ToolCall("search", "tool_search", Map.of("query", "select:recall_memory")),
        TurnCancellation.none(),
        session,
        context);
    ToolResult result =
        registry.execute(
            new ToolCall("recall", "recall_memory", Map.of("query", " cache ")),
            TurnCancellation.none(),
            session,
            context);

    JsonNode payload = JSON.readTree(result.content());
    assertThat(result.status().name()).isEqualTo("SUCCESS");
    assertThat(payload.path("count").asInt()).isEqualTo(1);
    assertThat(payload.path("limit").asInt()).isEqualTo(8);
    assertThat(payload.path("items").get(0).properties())
        .extracting(Map.Entry::getKey)
        .containsExactly("id", "memory_type", "content", "score");
    assertThat(payload.path("items").get(0).path("score").asText()).isEqualTo("0.8123");
    assertThat(result.content())
        .doesNotContain("telegram:123456789")
        .doesNotContain("scope")
        .doesNotContain("hotness")
        .doesNotContain("updated_at");
  }

  @Test
  void rejectsInvalidArgumentsAndMapsScopeAndInternalFailureToStableCodes() {
    ToolRegistry registry = registry();
    ToolCatalogSession session = unlock(registry);

    assertThat(
            execute(registry, session, ToolInvocationContext.none(), Map.of("query", "cache"))
                .content())
        .isEqualTo("MEMORY_RECALL_UNAVAILABLE");
    assertThat(
            execute(
                    registry,
                    session,
                    ToolInvocationContext.none().withMemoryRecall(scope()),
                    Map.of("query", "cache", "memory_type", "fact"))
                .content())
        .isEqualTo("MEMORY_RECALL_INVALID_ARGUMENT");
    assertThat(
            execute(
                    registry,
                    session,
                    ToolInvocationContext.none().withMemoryRecall(scope()),
                    Map.of("query", "cache", "limit", 1.0d))
                .content())
        .isEqualTo("MEMORY_RECALL_INVALID_ARGUMENT");
    assertThat(
            execute(
                    registry,
                    session,
                    ToolInvocationContext.none().withMemoryRecall(scope()),
                    Map.of("query", "cache", "limit", new BigDecimal("1.0")))
                .content())
        .isEqualTo("MEMORY_RECALL_INVALID_ARGUMENT");
    assertThat(
            execute(
                    registry,
                    session,
                    ToolInvocationContext.none().withMemoryRecall(scope()),
                    Map.of("query", "cache", "session", "telegram:123456789"))
                .content())
        .isEqualTo("MEMORY_RECALL_INVALID_ARGUMENT");
    assertThat(
            execute(
                    registry,
                    session,
                    ToolInvocationContext.none()
                        .withMemoryRecall(
                            (query, type, limit) -> {
                              throw new IllegalStateException("private sqlite failure");
                            }),
                    Map.of("query", "cache"))
                .content())
        .isEqualTo("MEMORY_RECALL_UNAVAILABLE");
  }

  @Test
  void rejectsOversizedProjectionWithoutTruncation() {
    ToolRegistry registry = registry();
    ToolCatalogSession session = unlock(registry);
    ToolInvocationContext context =
        ToolInvocationContext.none()
            .withMemoryRecall(
                (query, type, limit) ->
                    List.of(
                        hit("large-1", "x".repeat(4_000), 0.9),
                        hit("large-2", "x".repeat(4_000), 0.8),
                        hit("large-3", "x".repeat(4_000), 0.7),
                        hit("large-4", "x".repeat(4_000), 0.6)));

    assertThat(execute(registry, session, context, Map.of("query", "cache")).content())
        .isEqualTo("MEMORY_RECALL_UNAVAILABLE");
  }

  private static ToolRegistry registry() {
    return new ToolRegistry(
        new ToolCatalog(
            MemoryRecallToolset.enabled().tools().stream()
                .map(
                    tool ->
                        new ToolCatalogEntry(
                            tool,
                            ToolCatalogVisibility.DEFERRED,
                            ToolCatalogSource.BUILTIN,
                            "",
                            List.of("记忆", "memory", "recall")))
                .toList()),
        ToolRuntimeSettings.readOnlyDefaults());
  }

  private static ToolCatalogSession unlock(ToolRegistry registry) {
    ToolCatalogSession session = registry.newCatalogSession();
    registry.execute(
        new ToolCall("search", "tool_search", Map.of("query", "select:recall_memory")),
        TurnCancellation.none(),
        session);
    return session;
  }

  private static ToolResult execute(
      ToolRegistry registry,
      ToolCatalogSession session,
      ToolInvocationContext context,
      Map<String, Object> arguments) {
    return registry.execute(
        new ToolCall("recall", "recall_memory", arguments),
        TurnCancellation.none(),
        session,
        context);
  }

  private static MemoryRecallScope scope() {
    return (query, type, limit) -> List.of(hit("memory-0001", "cache fact", 0.81234));
  }

  private static MemorySearchHit hit(String id, String content, double score) {
    Instant now = Instant.parse("2026-07-19T00:00:00Z");
    MemoryItem item =
        new MemoryItem(
            id,
            new MemoryScope("a".repeat(64)),
            MemoryType.FACT,
            content,
            "b".repeat(64),
            new EmbeddingVector(new float[] {1.0f, 0.0f}),
            "model",
            1,
            0,
            MemorySourceKind.EXPLICIT_API,
            null,
            1,
            now,
            now);
    return new MemorySearchHit(item, score, 0.1, score);
  }
}
