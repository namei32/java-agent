package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.MemoryStorePort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.tool.ToolCall;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("failure")
class MemoryRecallChatServiceIntegrationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void bindsRecallToTheChatServicesPrivateSessionBindingAndReturnsOnlyProjection() {
    String sessionId = "telegram:123456789";
    MemoryScope scope = new MemoryScope(ApprovalFingerprint.sessionBinding(sessionId));
    var store = new RecordingStore(List.of(item(scope)));
    var requests = new ArrayList<ChatModelRequest>();
    ChatModelPort model =
        request -> {
          requests.add(request);
          return switch (requests.size()) {
            case 1 ->
                new ChatModelResponse(
                    "",
                    List.of(
                        new ToolCall(
                            "search", "tool_search", Map.of("query", "select:recall_memory"))));
            case 2 ->
                new ChatModelResponse(
                    "",
                    List.of(
                        new ToolCall(
                            "recall", "recall_memory", Map.of("query", "cache", "limit", 1))));
            case 3 -> new ChatModelResponse("已找到缓存事实");
            default -> throw new AssertionError("意外的模型调用");
          };
        };
    var recalls =
        MemoryRecallContextFactory.enabled(
            new ReadOnlyMemoryRecallService(
                store,
                request ->
                    new EmbeddingResult(
                        "model", 2, List.of(new EmbeddingVector(new float[] {1, 0}))),
                new SemanticMemoryRetrievalSettings("model", 2, 8, -1.0, 0.0, 14.0, 100, 6_000),
                CLOCK));
    var catalog =
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
                .toList());
    var service =
        new ChatService(
            new RecordingRepository(),
            model,
            new ConversationHistorySelector(),
            new HistoryLimits(40, 100_000),
            new KeyedSessionExecutionGate(Duration.ofSeconds(1)),
            "系统提示",
            CLOCK,
            catalog,
            6,
            event -> {},
            ToolRuntimeSettings.readOnlyDefaults(),
            request -> ApprovalDecision.deniedFor(request, CLOCK.instant(), "deny-all"),
            SideEffectLedger.unavailable(),
            new SecureIdGenerator(),
            Duration.ofMinutes(5),
            MemoryContextService.disabled(),
            ModelStreamingSettings.defaults(),
            ConversationEvidenceContextFactory.disabled(),
            recalls);

    assertThat(service.chat(new ChatCommand(sessionId, "请检索缓存")).assistant().content())
        .isEqualTo("已找到缓存事实");
    assertThat(store.request.scope()).isEqualTo(scope);
    assertThat(store.request.scope().binding()).doesNotContain(sessionId);
    String result = toolContent(requests.get(2));
    assertThat(result)
        .contains("cache fact")
        .doesNotContain(sessionId)
        .doesNotContain(scope.binding())
        .doesNotContain("hotness")
        .doesNotContain("embedding");
  }

  private static String toolContent(ChatModelRequest request) {
    return request.messages().stream()
        .filter(ToolResultMessage.class::isInstance)
        .map(ToolResultMessage.class::cast)
        .filter(message -> message.toolName().equals("recall_memory"))
        .findFirst()
        .orElseThrow()
        .content();
  }

  private static MemoryItem item(MemoryScope scope) {
    Instant now = CLOCK.instant();
    return new MemoryItem(
        "memory-0001",
        scope,
        MemoryType.FACT,
        "cache fact",
        "a".repeat(64),
        new EmbeddingVector(new float[] {1, 0}),
        "model",
        1,
        0,
        MemorySourceKind.EXPLICIT_API,
        null,
        1,
        now,
        now);
  }

  private static final class RecordingStore implements MemoryStorePort {
    private final List<MemoryItem> items;
    private MemorySearchRequest request;

    private RecordingStore(List<MemoryItem> items) {
      this.items = items;
    }

    @Override
    public long candidateCount(MemoryScope scope) {
      return items.size();
    }

    @Override
    public List<MemoryItem> loadCandidates(MemorySearchRequest request) {
      this.request = request;
      return items;
    }

    @Override
    public List<MemoryItem> list(MemoryScope scope, int limit) {
      return items;
    }
  }

  private static final class RecordingRepository implements SessionRepository {
    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 0);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {}
  }
}
