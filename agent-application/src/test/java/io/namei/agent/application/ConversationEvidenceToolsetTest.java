package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.evidence.ConversationEvidenceMessage;
import io.namei.agent.kernel.evidence.ConversationEvidencePage;
import io.namei.agent.kernel.evidence.ConversationEvidenceReference;
import io.namei.agent.kernel.evidence.ConversationEvidenceRole;
import io.namei.agent.kernel.evidence.ConversationEvidenceSearchQuery;
import io.namei.agent.kernel.port.ConversationEvidencePort;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("failure")
class ConversationEvidenceToolsetTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void deferredToolsRequireSearchAndProjectOnlyOpaqueCurrentSessionEvidence() throws Exception {
    RecordingEvidencePort port = new RecordingEvidencePort();
    ToolRegistry registry = new ToolRegistry(catalog(), ToolRuntimeSettings.readOnlyDefaults());
    ToolCatalogSession session = registry.newCatalogSession();
    ToolInvocationContext context =
        ConversationEvidenceContextFactory.enabled(port).forSession("telegram:123456789");

    assertThat(
            registry
                .execute(
                    new ToolCall("direct", "fetch_messages", Map.of("ids", List.of("msg-v1:2"))),
                    TurnCancellation.none(),
                    session,
                    context)
                .content())
        .isEqualTo("工具不可用。");

    registry.execute(
        new ToolCall(
            "search", "tool_search", Map.of("query", "select:fetch_messages,search_messages")),
        TurnCancellation.none(),
        session,
        context);
    ToolResult result =
        registry.execute(
            new ToolCall(
                "fetch", "fetch_messages", Map.of("ids", List.of("msg-v1:2"), "context", 1)),
            TurnCancellation.none(),
            session,
            context);

    JsonNode payload = JSON.readTree(result.content());
    assertThat(result.status().name()).isEqualTo("SUCCESS");
    assertThat(payload.path("count").asInt()).isEqualTo(2);
    assertThat(payload.path("matched_count").asInt()).isEqualTo(1);
    assertThat(payload.path("messages").get(0).path("id").asString()).isEqualTo("msg-v1:1");
    assertThat(payload.path("messages").get(1).path("in_source_ref").asBoolean()).isTrue();
    assertThat(result.content())
        .doesNotContain("telegram:123456789")
        .doesNotContain("internal_tool_chain")
        .doesNotContain("session_key");
    assertThat(port.sessions).containsExactly("telegram:123456789", "telegram:123456789");
  }

  @Test
  void strictArgumentAndProjectionFailuresUseStableSafeCodes() {
    RecordingEvidencePort port = new RecordingEvidencePort();
    ToolRegistry registry = unlockedRegistry();
    ToolCatalogSession session = unlock(registry);
    ToolInvocationContext context =
        ConversationEvidenceContextFactory.enabled(port).forSession("private");

    assertThat(
            registry
                .execute(
                    new ToolCall(
                        "bad-id", "fetch_messages", Map.of("ids", List.of("telegram:123456789:2"))),
                    TurnCancellation.none(),
                    session,
                    context)
                .content())
        .isEqualTo("CONVERSATION_EVIDENCE_INVALID_ARGUMENT");
    assertThat(
            registry
                .execute(
                    new ToolCall(
                        "bad-query", "search_messages", Map.of("query", "cache", "limit", 1.0d)),
                    TurnCancellation.none(),
                    session,
                    context)
                .content())
        .isEqualTo("CONVERSATION_EVIDENCE_INVALID_ARGUMENT");

    port.fail = true;
    assertThat(
            registry
                .execute(
                    new ToolCall("unavailable", "search_messages", Map.of("query", "cache")),
                    TurnCancellation.none(),
                    session,
                    context)
                .content())
        .isEqualTo("CONVERSATION_EVIDENCE_UNAVAILABLE");
  }

  @Test
  void searchProjectsBoundedPreviewAndFetchFailsSafelyForAnOversizedWindow() throws Exception {
    RecordingEvidencePort port = new RecordingEvidencePort();
    ToolRegistry registry = unlockedRegistry();
    ToolCatalogSession session = unlock(registry);
    ToolInvocationContext context =
        ConversationEvidenceContextFactory.enabled(port).forSession("private");

    ToolResult search =
        registry.execute(
            new ToolCall("search", "search_messages", Map.of("query", "cache")),
            TurnCancellation.none(),
            session,
            context);

    JsonNode payload = JSON.readTree(search.content());
    assertThat(search.status().name()).isEqualTo("SUCCESS");
    assertThat(payload.path("citation_required").asBoolean()).isTrue();
    assertThat(payload.path("messages").get(0).path("source_ref").asString()).isEqualTo("msg-v1:2");
    assertThat(payload.path("messages").get(0).path("preview_line_count").asInt()).isEqualTo(50);
    assertThat(payload.path("messages").get(0).path("truncated").asBoolean()).isTrue();

    port.windowSize = 17;
    assertThat(
            registry
                .execute(
                    new ToolCall(
                        "wide-window",
                        "fetch_messages",
                        Map.of("ids", List.of("msg-v1:2"), "context", 10)),
                    TurnCancellation.none(),
                    session,
                    context)
                .content())
        .isEqualTo("CONVERSATION_EVIDENCE_UNAVAILABLE");
  }

  @Test
  void contextsRemainExplicitPerInvocationAndOrdinaryToolsKeepLegacyApi() throws Exception {
    RecordingEvidencePort port = new RecordingEvidencePort();
    Tool ordinary =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return new ToolDefinition(
                "ordinary",
                "ordinary tool",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                ToolRisk.READ_ONLY);
          }

          @Override
          public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success("ordinary-ok");
          }
        };
    var entries = new ArrayList<ToolCatalogEntry>();
    for (Tool tool : ConversationEvidenceToolset.enabled().tools()) {
      entries.add(
          new ToolCatalogEntry(
              tool,
              ToolCatalogVisibility.DEFERRED,
              ToolCatalogSource.BUILTIN,
              "",
              List.of("历史消息")));
    }
    entries.add(
        new ToolCatalogEntry(
            ordinary, ToolCatalogVisibility.ALWAYS_ON, ToolCatalogSource.BUILTIN, "", List.of()));
    ToolRegistry registry =
        new ToolRegistry(new ToolCatalog(entries), ToolRuntimeSettings.readOnlyDefaults());
    ToolCatalogSession first = unlock(registry);
    ToolCatalogSession second = unlock(registry);
    var factory = ConversationEvidenceContextFactory.enabled(port);

    ToolResult firstResult =
        registry.execute(
            new ToolCall("first", "fetch_messages", Map.of("ids", List.of("msg-v1:2"))),
            TurnCancellation.none(),
            first,
            factory.forSession("session-one"));
    ToolResult secondResult =
        registry.execute(
            new ToolCall("second", "fetch_messages", Map.of("ids", List.of("msg-v1:2"))),
            TurnCancellation.none(),
            second,
            factory.forSession("session-two"));
    ToolResult ordinaryResult =
        registry.execute(
            new ToolCall("ordinary", "ordinary", Map.of()),
            TurnCancellation.none(),
            second,
            factory.forSession("session-three"));

    assertThat(firstResult.content()).contains("session-one-content").doesNotContain("session-two");
    assertThat(secondResult.content())
        .contains("session-two-content")
        .doesNotContain("session-one");
    assertThat(ordinaryResult.content()).isEqualTo("ordinary-ok");
    assertThat(port.sessions).containsExactly("session-one", "session-two");
  }

  private static ToolCatalog catalog() {
    return new ToolCatalog(
        ConversationEvidenceToolset.enabled().tools().stream()
            .map(
                tool ->
                    new ToolCatalogEntry(
                        tool,
                        ToolCatalogVisibility.DEFERRED,
                        ToolCatalogSource.BUILTIN,
                        "",
                        List.of("历史消息", "conversation evidence")))
            .toList());
  }

  private static ToolRegistry unlockedRegistry() {
    return new ToolRegistry(catalog(), ToolRuntimeSettings.readOnlyDefaults());
  }

  private static ToolCatalogSession unlock(ToolRegistry registry) {
    ToolCatalogSession session = registry.newCatalogSession();
    registry.execute(
        new ToolCall(
            "search", "tool_search", Map.of("query", "select:fetch_messages,search_messages")),
        TurnCancellation.none(),
        session);
    return session;
  }

  private static final class RecordingEvidencePort implements ConversationEvidencePort {
    private final ConcurrentLinkedQueue<String> sessions = new ConcurrentLinkedQueue<>();
    private boolean fail;
    private int windowSize = 2;

    @Override
    public List<ConversationEvidenceMessage> fetch(
        String sessionId, List<ConversationEvidenceReference> references) {
      sessions.add(sessionId);
      throwIfNeeded();
      return references.stream()
          .map(
              reference ->
                  new ConversationEvidenceMessage(
                      reference, ConversationEvidenceRole.USER, contentFor(sessionId)))
          .toList();
    }

    @Override
    public List<ConversationEvidenceMessage> fetchWindow(
        String sessionId, List<ConversationEvidenceReference> references, int context) {
      sessions.add(sessionId);
      throwIfNeeded();
      ConversationEvidenceReference source = references.getFirst();
      if (windowSize > 2) {
        return java.util.stream.IntStream.range(0, windowSize)
            .mapToObj(
                sequence ->
                    new ConversationEvidenceMessage(
                        new ConversationEvidenceReference(sequence),
                        ConversationEvidenceRole.USER,
                        contentFor(sessionId)))
            .toList();
      }
      return List.of(
          new ConversationEvidenceMessage(
              new ConversationEvidenceReference(source.sequence() - 1),
              ConversationEvidenceRole.ASSISTANT,
              "context"),
          new ConversationEvidenceMessage(
              source, ConversationEvidenceRole.USER, contentFor(sessionId)));
    }

    @Override
    public ConversationEvidencePage search(
        String sessionId, ConversationEvidenceSearchQuery query) {
      sessions.add(sessionId);
      throwIfNeeded();
      return new ConversationEvidencePage(
          List.of(
              new ConversationEvidenceMessage(
                  new ConversationEvidenceReference(2),
                  ConversationEvidenceRole.USER,
                  contentFor(sessionId) + " cache\n" + "line\n".repeat(55))),
          1,
          false);
    }

    private void throwIfNeeded() {
      if (fail) {
        throw new IllegalStateException("private store detail");
      }
    }

    private static String contentFor(String sessionId) {
      return switch (sessionId) {
        case "session-one" -> "session-one-content";
        case "session-two" -> "session-two-content";
        default -> "historical-content";
      };
    }
  }
}
