package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnResolution;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class MemoryForgetPendingToolLoopTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void unlocksForgetOnTheNextRequestThenCreatesPendingWithoutSendingAToolResultBackToTheModel() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    var pending = MemoryForgetPendingToolset.enabled(service(store, sessions));
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "", List.of(new ToolCall("search", "tool_search", Map.of("query", "遗忘 记忆")))),
            new ChatModelResponse(
                "",
                List.of(
                    new ToolCall("forget", "forget_memory", Map.of("ids", List.of("memory-a"))))));

    ToolLoopCompletion completion =
        loop(catalog(pending), model, pending)
            .completeForPendingTurn(
                List.of(new ChatMessage(MessageRole.USER, "请遗忘记忆")),
                TurnCancellation.none(),
                new SideEffectBatchCoordinator.Context("session-binding", "turn-1"),
                ToolInvocationContext.none(),
                new MemoryForgetPendingTurnContext(
                    "session-java-memory-001",
                    0,
                    "turn-1",
                    new ChatMessage(MessageRole.USER, "请遗忘记忆"),
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                    CLOCK));

    assertThat(completion)
        .isEqualTo(
            new ToolLoopCompletion.Pending(
                MemoryForgetPendingToolset.pendingAssistantProjection()));
    assertThat(model.requests)
        .extracting(request -> names(request.tools()))
        .containsExactly(
            List.of("current_time", "tool_search"),
            List.of("current_time", "tool_search", "forget_memory"));
    assertThat(store.events).containsExactly("create");
    assertThat(sessions.events).containsExactly("append-pending");
    assertThat(sessions.pendingTurn.assistant().content())
        .isEqualTo(MemoryForgetPendingToolset.pendingAssistantProjection());
  }

  @Test
  void chatReturnsTheFixedPendingProjectionWithoutAppendingASecondOrdinaryTurn() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    var pending = MemoryForgetPendingToolset.enabled(service(store, sessions));
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "", List.of(new ToolCall("search", "tool_search", Map.of("query", "遗忘 记忆")))),
            new ChatModelResponse(
                "",
                List.of(
                    new ToolCall("forget", "forget_memory", Map.of("ids", List.of("memory-a"))))));
    var settings =
        new ToolRuntimeSettings(
            ToolRuntimeMode.APPROVAL_REQUIRED, 8, 16, Duration.ofSeconds(5), 32, 20_000);
    var service =
        new ChatService(
            sessions,
            model,
            new io.namei.agent.kernel.history.ConversationHistorySelector(),
            new io.namei.agent.kernel.history.HistoryLimits(40, 100_000),
            new SessionExecutionGate() {
              @Override
              public <T> T execute(String sessionId, java.util.function.Supplier<T> action) {
                return action.get();
              }
            },
            "system",
            CLOCK,
            catalog(pending),
            3,
            TurnLifecycleObserver.noop(),
            settings,
            request -> {
              throw new AssertionError("Pending Producer 不得请求泛化审批");
            },
            SideEffectLedger.unavailable(),
            new FixedIds(),
            Duration.ofMinutes(5),
            MemoryContextService.disabled(),
            ModelStreamingSettings.defaults(),
            ConversationEvidenceContextFactory.disabled(),
            MemoryRecallContextFactory.disabled(),
            ContextLimitRecoveryPolicy.disabled(),
            io.namei.agent.kernel.port.ProviderUsageObserver.disabled(),
            pending);

    ChatResult result = service.chat(new ChatCommand("session-java-memory-001", "请遗忘记忆"));

    assertThat(result.assistant().content())
        .isEqualTo(MemoryForgetPendingToolset.pendingAssistantProjection());
    assertThat(sessions.events).containsExactly("append-pending");
    assertThat(store.events).containsExactly("create");
    assertThat(model.requests).hasSize(2);
  }

  @Test
  void leavesChannelSessionIdsOnTheOrdinaryPathWhenThePendingProducerIsDisabled() {
    var sessions = new OrdinarySessions();
    var model = new ScriptedModel(new ChatModelResponse("普通回答"));
    var service =
        new ChatService(
            sessions,
            model,
            new io.namei.agent.kernel.history.ConversationHistorySelector(),
            new io.namei.agent.kernel.history.HistoryLimits(40, 100_000),
            new SessionExecutionGate() {
              @Override
              public <T> T execute(String sessionId, java.util.function.Supplier<T> action) {
                return action.get();
              }
            },
            "system",
            CLOCK);

    ChatResult result = service.chat(new ChatCommand("telegram:10001", "普通问题"));

    assertThat(result.assistant().content()).isEqualTo("普通回答");
    assertThat(model.requests).hasSize(1);
    assertThat(sessions.messages).containsExactly("普通问题", "普通回答");
  }

  @Test
  void returnsTheExistingSafeEmptyResultWithoutCreatingAnyDurableOperation() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    var pending = MemoryForgetPendingToolset.enabled(service(store, sessions));
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "", List.of(new ToolCall("search", "tool_search", Map.of("query", "遗忘 记忆")))),
            new ChatModelResponse(
                "",
                List.of(new ToolCall("forget", "forget_memory", Map.of("ids", List.of(" ", ""))))),
            new ChatModelResponse("已处理空请求。"));

    ToolLoopCompletion completion =
        loop(catalog(pending), model, pending)
            .completeForPendingTurn(
                List.of(new ChatMessage(MessageRole.USER, "请遗忘记忆")),
                TurnCancellation.none(),
                new SideEffectBatchCoordinator.Context("session-binding", "turn-1"),
                ToolInvocationContext.none(),
                new MemoryForgetPendingTurnContext(
                    "session-java-memory-001",
                    0,
                    "turn-1",
                    new ChatMessage(MessageRole.USER, "请遗忘记忆"),
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                    CLOCK));

    assertThat(completion).isEqualTo(new ToolLoopCompletion.Final("已处理空请求。"));
    assertThat(store.events).isEmpty();
    assertThat(sessions.events).isEmpty();
    assertThat(model.requests).hasSize(3);
  }

  @Test
  @Tag("failure")
  void rejectsSearchAndForgetInTheSameBatchBeforeAnyPendingWriteOrGenericApproval() {
    var store = new RecordingStore();
    var sessions = new RecordingSessions();
    var pending = MemoryForgetPendingToolset.enabled(service(store, sessions));
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "",
                List.of(
                    new ToolCall("search", "tool_search", Map.of("query", "遗忘 记忆")),
                    new ToolCall("forget", "forget_memory", Map.of("ids", List.of("memory-a"))))));

    assertThatThrownBy(
            () ->
                loop(catalog(pending), model, pending)
                    .completeForPendingTurn(
                        List.of(new ChatMessage(MessageRole.USER, "请遗忘记忆")),
                        TurnCancellation.none(),
                        new SideEffectBatchCoordinator.Context("session-binding", "turn-1"),
                        ToolInvocationContext.none(),
                        new MemoryForgetPendingTurnContext(
                            "session-java-memory-001",
                            0,
                            "turn-1",
                            new ChatMessage(MessageRole.USER, "请遗忘记忆"),
                            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                            CLOCK)))
        .isInstanceOf(io.namei.agent.kernel.error.InvalidModelResponseException.class);

    assertThat(store.events).isEmpty();
    assertThat(sessions.events).isEmpty();
  }

  private static ToolLoop loop(
      ToolCatalog catalog, ChatModelPort model, MemoryForgetPendingToolset pending) {
    var settings =
        new ToolRuntimeSettings(
            ToolRuntimeMode.APPROVAL_REQUIRED, 8, 16, Duration.ofSeconds(5), 32, 20_000);
    var registry = new ToolRegistry(catalog, settings);
    var lifecycle = new LifecyclePublisher(TurnLifecycleObserver.noop());
    return new ToolLoop(
        model,
        registry,
        lifecycle,
        3,
        settings,
        new SideEffectBatchCoordinator(
            registry,
            request -> {
              throw new AssertionError("Pending Producer 不得请求泛化审批");
            },
            ToolExecutionPolicy.registeredRisk(),
            CLOCK,
            Duration.ofMinutes(5),
            new FixedIds(),
            SideEffectLedger.unavailable(),
            lifecycle),
        ModelStreamingSettings.defaults(),
        pending);
  }

  private static ToolCatalog catalog(MemoryForgetPendingToolset pending) {
    Tool currentTime =
        new Tool() {
          private final ToolDefinition definition =
              new ToolDefinition(
                  "current_time",
                  "返回当前 UTC 时间",
                  Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                  ToolRisk.READ_ONLY);

          @Override
          public ToolDefinition definition() {
            return definition;
          }

          @Override
          public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success("ok");
          }
        };
    return new ToolCatalog(
        List.of(
            new ToolCatalogEntry(
                currentTime,
                ToolCatalogVisibility.ALWAYS_ON,
                ToolCatalogSource.BUILTIN,
                "",
                List.of()),
            new ToolCatalogEntry(
                pending.tools().getFirst(),
                ToolCatalogVisibility.DEFERRED,
                ToolCatalogSource.BUILTIN,
                "",
                List.of("遗忘", "记忆", "forget", "memory"))));
  }

  private static MemoryForgetPendingService service(
      RecordingStore store, RecordingSessions sessions) {
    return new MemoryForgetPendingService(
        store,
        sessions,
        () -> PendingOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
        () -> ApprovalInboxReference.of("AQEBAQEBAQEBAQEBAQEBAQ"),
        new FixedIds(),
        CLOCK,
        Duration.ofMinutes(5));
  }

  private static List<String> names(List<ToolDefinition> definitions) {
    return definitions.stream().map(ToolDefinition::name).toList();
  }

  private static final class ScriptedModel implements ChatModelPort {
    private final ArrayDeque<ChatModelResponse> responses;
    private final List<ChatModelRequest> requests = new ArrayList<>();

    private ScriptedModel(ChatModelResponse... responses) {
      responses = responses.clone();
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      return responses.removeFirst();
    }
  }

  private static final class FixedIds implements IdGenerator {
    @Override
    public String newTurnId() {
      return "unused-turn";
    }

    @Override
    public String newApprovalId() {
      return "approval-id";
    }

    @Override
    public String newIdempotencyKey() {
      return "idempotency-key";
    }
  }

  private static final class RecordingStore implements PendingOperationStore {
    private final List<String> events = new ArrayList<>();

    @Override
    public PendingOperation create(
        PendingOperation operation, ApprovalInboxEntry approval, PendingOperationCapsule capsule) {
      events.add("create");
      return operation;
    }

    @Override
    public Optional<PendingOperation> find(PendingOperationReference reference) {
      return Optional.empty();
    }

    @Override
    public Optional<PendingOperationCapsule> loadVerifiedCapsule(
        PendingOperationReference reference) {
      return Optional.empty();
    }

    @Override
    public boolean markStaleSessionIfPending(
        PendingOperationReference reference, Instant observedAt) {
      events.add("stale");
      return true;
    }

    @Override
    public PendingOperationReservation reserveApproved(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markRunning(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markSucceeded(
        PendingOperationReference reference, ToolResult safeResult, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markFailedBeforeStart(
        PendingOperationReference reference, ToolResult safeResult, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markUnknown(
        PendingOperationReference reference, String errorCode, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperation markCommitUnreported(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingOperationLedgerEntry> findLedger(PendingOperationReference reference) {
      return Optional.empty();
    }
  }

  private static final class RecordingSessions implements SessionRepository {
    private final List<String> events = new ArrayList<>();
    private PersistedTurn pendingTurn;

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 0);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean appendPendingTurnIfNextSequence(
        PersistedTurn pendingTurn, PendingTurnAnchor anchor) {
      events.add("append-pending");
      this.pendingTurn = pendingTurn;
      return true;
    }

    @Override
    public Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
      return Optional.empty();
    }

    @Override
    public boolean appendPendingResolutionIfAnchorMatches(
        PendingTurnAnchor anchor, PendingTurnResolution resolution) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class OrdinarySessions implements SessionRepository {
    private final List<String> messages = new ArrayList<>();

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 0);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      messages.add(turn.user().content());
      messages.add(turn.assistant().content());
    }
  }
}
