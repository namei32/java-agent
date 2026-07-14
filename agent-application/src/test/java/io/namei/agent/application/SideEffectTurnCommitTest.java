package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SideEffectTurnCommitTest {
  private static final Instant NOW = Instant.parse("2026-07-14T05:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void keepsSucceededLedgerButDoesNotCommitWhenFollowingModelCallFails() {
    var ledger = new InMemorySideEffectLedger();
    var captured = new AtomicReference<io.namei.agent.kernel.approval.ApprovalRequest>();
    ApprovalPort approve =
        request -> {
          captured.set(request);
          return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
        };
    var repository = new RecordingRepository(false);
    var model = new FailingContinuationModel();
    var invocations = new AtomicInteger();

    assertThatThrownBy(
            () ->
                service(repository, model, invocations, new ArrayList<>(), approve, ledger)
                    .chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("call-1");

    assertThat(invocations).hasValue(1);
    assertThat(repository.appended).isEmpty();
    assertThat(ledger.find(SideEffectIdentity.from(captured.get())))
        .get()
        .extracting(SideEffectLedger.Entry::state)
        .isEqualTo(SideEffectExecutionState.SUCCEEDED);
  }

  @Test
  void keepsSucceededLedgerButDoesNotCommitWhenConversationAppendFails() {
    var ledger = new InMemorySideEffectLedger();
    var captured = new AtomicReference<io.namei.agent.kernel.approval.ApprovalRequest>();
    ApprovalPort approve =
        request -> {
          captured.set(request);
          return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
        };
    var repository = new RecordingRepository(true);
    var model =
        new SequenceModel(
            new ChatModelResponse(
                "", List.of(new ToolCall("call-1", "write_note", Map.of()))),
            new ChatModelResponse("最终回答"));

    assertThatThrownBy(
            () ->
                service(
                        repository,
                        model,
                        new AtomicInteger(),
                        new ArrayList<>(),
                        approve,
                        ledger)
                    .chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(repository.appended).isEmpty();
    assertThat(ledger.find(SideEffectIdentity.from(captured.get())))
        .get()
        .extracting(SideEffectLedger.Entry::state)
        .isEqualTo(SideEffectExecutionState.SUCCEEDED);
  }

  @Test
  void unknownStateFailsTurnWithoutCallingModelAgainOrCommitting() {
    var ledger = new InMemorySideEffectLedger();
    ApprovalPort seedUnknown =
        request -> {
          ledger.seed(SideEffectIdentity.from(request), SideEffectExecutionState.UNKNOWN, null);
          return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
        };
    var repository = new RecordingRepository(false);
    var model =
        new SequenceModel(
            new ChatModelResponse(
                "", List.of(new ToolCall("call-1", "write_note", Map.of()))),
            new ChatModelResponse("不应请求"));
    var events = new ArrayList<TurnLifecycleEvent>();
    var invocations = new AtomicInteger();

    assertThatThrownBy(
            () ->
                service(repository, model, invocations, events, seedUnknown, ledger)
                    .chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(SideEffectStateUnknownException.class);

    assertThat(model.requests).hasSize(1);
    assertThat(invocations).hasValue(0);
    assertThat(repository.appended).isEmpty();
    assertThat(events.getLast().status()).isEqualTo("SIDE_EFFECT_STATE_UNKNOWN");
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      AtomicInteger invocations,
      List<TurnLifecycleEvent> events,
      ApprovalPort approvals,
      SideEffectLedger ledger) {
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000),
        directGate(),
        "系统提示",
        CLOCK,
        List.of(sideEffectTool(invocations)),
        3,
        events::add,
        new ToolRuntimeSettings(
            ToolRuntimeMode.APPROVAL_REQUIRED,
            8,
            16,
            Duration.ofSeconds(5),
            32,
            20_000),
        approvals,
        ledger,
        new FixedIds(),
        Duration.ofMinutes(5));
  }

  private static Tool sideEffectTool(AtomicInteger invocations) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(
            "write_note",
            "测试副作用工具",
            Map.of("type", "object", "additionalProperties", false),
            ToolRisk.WRITE);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        invocations.incrementAndGet();
        return ToolResult.success("固定成功");
      }
    };
  }

  private static SessionExecutionGate directGate() {
    return new SessionExecutionGate() {
      @Override
      public <T> T execute(String sessionId, Supplier<T> action) {
        return action.get();
      }
    };
  }

  private static final class FailingContinuationModel implements ChatModelPort {
    private int calls;

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      if (calls++ == 0) {
        return new ChatModelResponse(
            "", List.of(new ToolCall("call-1", "write_note", Map.of())));
      }
      throw new IllegalStateException("private model failure");
    }
  }

  private static final class SequenceModel implements ChatModelPort {
    private final java.util.ArrayDeque<ChatModelResponse> responses;
    private final List<ChatModelRequest> requests = new ArrayList<>();

    private SequenceModel(ChatModelResponse... responses) {
      this.responses = new java.util.ArrayDeque<>(List.of(responses));
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      return responses.removeFirst();
    }
  }

  private static final class RecordingRepository implements SessionRepository {
    private final boolean failAppend;
    private final List<PersistedTurn> appended = new ArrayList<>();

    private RecordingRepository(boolean failAppend) {
      this.failAppend = failAppend;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 0);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      if (failAppend) {
        throw new IllegalStateException("private sqlite failure");
      }
      appended.add(turn);
    }
  }

  private static final class FixedIds implements IdGenerator {
    @Override
    public String newTurnId() {
      return "turn-fixed";
    }

    @Override
    public String newApprovalId() {
      return "approval-fixed";
    }

    @Override
    public String newIdempotencyKey() {
      return "idempotency-fixed";
    }
  }
}
