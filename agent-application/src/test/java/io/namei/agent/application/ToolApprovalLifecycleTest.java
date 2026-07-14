package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.lifecycle.TurnEventType;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ToolApprovalLifecycleTest {
  private static final Instant NOW = Instant.parse("2026-07-14T05:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void emitsApprovalAndSideEffectLifecycleInBoundaryOrder() {
    String privateArgument = "private-argument-must-not-leak";
    String privateActor = "private-actor-must-not-leak";
    var model =
        new ScriptedModel(
            toolResponse(Map.of("value", privateArgument)), new ChatModelResponse("最终回答"));
    var repository = new RecordingRepository();
    var events = new ArrayList<TurnLifecycleEvent>();
    var invocations = new AtomicInteger();
    var ledger = new InMemorySideEffectLedger();
    ApprovalPort approve = request -> ApprovalDecision.approvedFor(request, NOW, privateActor);

    service(repository, model, invocations, events, approve, ledger)
        .chat(new ChatCommand("private-session", "问题"));

    assertThat(invocations).hasValue(1);
    assertThat(repository.appended).hasSize(1);
    assertThat(events)
        .extracting(TurnLifecycleEvent::type)
        .containsExactly(
            TurnEventType.TURN_STARTED,
            TurnEventType.MODEL_REQUESTED,
            TurnEventType.MODEL_COMPLETED,
            TurnEventType.TOOL_CALL_STARTED,
            TurnEventType.APPROVAL_REQUESTED,
            TurnEventType.APPROVAL_RESOLVED,
            TurnEventType.SIDE_EFFECT_STARTED,
            TurnEventType.SIDE_EFFECT_COMPLETED,
            TurnEventType.TOOL_CALL_COMPLETED,
            TurnEventType.MODEL_REQUESTED,
            TurnEventType.MODEL_COMPLETED,
            TurnEventType.TURN_COMMITTING,
            TurnEventType.TURN_COMMITTED);
    assertThat(events.stream().filter(event -> event.type() == TurnEventType.APPROVAL_RESOLVED))
        .singleElement()
        .extracting(TurnLifecycleEvent::status)
        .isEqualTo("APPROVED");
    assertThat(events.toString())
        .doesNotContain(privateArgument, privateActor, "private-session", "idempotency-fixed");
  }

  @Test
  void denialDoesNotCrossSideEffectBoundaryAndCanCommitAFinalExplanation() {
    var model = new ScriptedModel(toolResponse(Map.of()), new ChatModelResponse("未执行，已说明原因"));
    var repository = new RecordingRepository();
    var events = new ArrayList<TurnLifecycleEvent>();
    var invocations = new AtomicInteger();
    ApprovalPort deny = request -> ApprovalDecision.deniedFor(request, NOW, "actor-reference");

    service(repository, model, invocations, events, deny, new InMemorySideEffectLedger())
        .chat(new ChatCommand("demo", "问题"));

    assertThat(invocations).hasValue(0);
    assertThat(repository.appended).hasSize(1);
    assertThat(events)
        .extracting(TurnLifecycleEvent::type)
        .contains(TurnEventType.APPROVAL_REQUESTED, TurnEventType.APPROVAL_RESOLVED)
        .doesNotContain(TurnEventType.SIDE_EFFECT_STARTED, TurnEventType.SIDE_EFFECT_COMPLETED);
    assertThat(model.requests.get(1).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(message -> ((ToolResultMessage) message).status())
        .containsExactly(ToolResultStatus.DENIED);
  }

  @Test
  @Tag("failure")
  void cancellationAfterApprovalStopsExecutionModelContinuationAndCommit() {
    var cancellation = new TurnCancellationSource();
    var model = new ScriptedModel(toolResponse(Map.of()), new ChatModelResponse("不应请求"));
    var repository = new RecordingRepository();
    var events = new ArrayList<TurnLifecycleEvent>();
    var invocations = new AtomicInteger();
    ApprovalPort approveThenCancel =
        request -> {
          cancellation.cancel();
          return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
        };

    assertThatThrownBy(
            () ->
                service(
                        repository,
                        model,
                        invocations,
                        events,
                        approveThenCancel,
                        new InMemorySideEffectLedger())
                    .chat(new ChatCommand("demo", "问题"), cancellation.token()))
        .isInstanceOf(TurnCancelledException.class);

    assertThat(invocations).hasValue(0);
    assertThat(model.requests).hasSize(1);
    assertThat(repository.appended).isEmpty();
    assertThat(events)
        .extracting(TurnLifecycleEvent::type)
        .doesNotContain(TurnEventType.SIDE_EFFECT_STARTED, TurnEventType.SIDE_EFFECT_COMPLETED);
    assertThat(events.getLast().status()).isEqualTo("TURN_CANCELLED");
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
        settings(),
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
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string")),
                "additionalProperties",
                false),
            ToolRisk.WRITE);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        invocations.incrementAndGet();
        return ToolResult.success("private-result-must-not-leak");
      }
    };
  }

  private static ChatModelResponse toolResponse(Map<String, Object> arguments) {
    return new ChatModelResponse("", List.of(new ToolCall("call-1", "write_note", arguments)));
  }

  private static ToolRuntimeSettings settings() {
    return new ToolRuntimeSettings(
        ToolRuntimeMode.APPROVAL_REQUIRED, 8, 16, Duration.ofSeconds(5), 32, 20_000);
  }

  private static SessionExecutionGate directGate() {
    return new SessionExecutionGate() {
      @Override
      public <T> T execute(String sessionId, Supplier<T> action) {
        return action.get();
      }
    };
  }

  private static final class ScriptedModel implements ChatModelPort {
    private final ArrayDeque<ChatModelResponse> responses;
    private final List<ChatModelRequest> requests = new ArrayList<>();

    private ScriptedModel(ChatModelResponse... responses) {
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      return responses.removeFirst();
    }
  }

  private static final class RecordingRepository implements SessionRepository {
    private final List<PersistedTurn> appended = new ArrayList<>();

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 0);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
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
