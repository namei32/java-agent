package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ToolLoopTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void executesMultipleReadOnlyToolsInOrderAndCommitsOnlyFinalText() {
    var repository = new RecordingRepository();
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "正在查询",
                List.of(
                    new ToolCall("call-1", "first", Map.of()),
                    new ToolCall("call-2", "second", Map.of()))),
            new ChatModelResponse("最终回答"));
    var executionOrder = new ArrayList<String>();
    var events = new ArrayList<TurnLifecycleEvent>();
    var service =
        service(
            repository,
            model,
            List.of(
                tool("first", "第一结果", executionOrder),
                tool("second", "第二结果", executionOrder)),
            3,
            events::add);

    var result = service.chat(new ChatCommand("demo", "问题"));

    assertThat(result.assistant().content()).isEqualTo("最终回答");
    assertThat(executionOrder).containsExactly("first", "second");
    assertThat(model.requests).hasSize(2);
    assertThat(model.requests.get(1).messages())
        .anyMatch(AssistantToolCallMessage.class::isInstance)
        .filteredOn(ToolResultMessage.class::isInstance)
        .hasSize(2);
    assertThat(repository.appended).hasSize(1);
    assertThat(repository.appended.getFirst().user().content()).isEqualTo("问题");
    assertThat(repository.appended.getFirst().assistant().content()).isEqualTo("最终回答");
    assertThat(events)
        .extracting(event -> event.type().name())
        .containsExactly(
            "TURN_STARTED",
            "MODEL_REQUESTED",
            "MODEL_COMPLETED",
            "TOOL_CALL_STARTED",
            "TOOL_CALL_COMPLETED",
            "TOOL_CALL_STARTED",
            "TOOL_CALL_COMPLETED",
            "MODEL_REQUESTED",
            "MODEL_COMPLETED",
            "TURN_COMMITTING",
            "TURN_COMMITTED");
  }

  @Test
  void convertsUnknownToolsAndExceptionsToSafeErrorResultsAndContinues() {
    String privateFailure = "private tool failure must not leak";
    Tool failing =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return ToolLoopTest.definition("failing");
          }

          @Override
          public ToolResult execute(Map<String, Object> arguments) {
            throw new IllegalStateException(privateFailure);
          }
        };
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "",
                List.of(
                    new ToolCall("call-missing", "missing", Map.of()),
                    new ToolCall("call-failing", "failing", Map.of()))),
            new ChatModelResponse("已使用替代方案"));
    var repository = new RecordingRepository();
    var service = service(repository, model, List.of(failing), 3, event -> {});

    service.chat(new ChatCommand("demo", "问题"));

    assertThat(model.requests.get(1).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(message -> (ToolResultMessage) message)
        .extracting(ToolResultMessage::status)
        .containsExactly(ToolResultStatus.ERROR, ToolResultStatus.ERROR);
    assertThat(model.requests.get(1).messages().toString()).doesNotContain(privateFailure);
    assertThat(repository.appended).hasSize(1);
  }

  @Test
  void failsWithoutCommitWhenIterationBudgetIsExhausted() {
    var repository = new RecordingRepository();
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "", List.of(new ToolCall("call-1", "lookup", Map.of()))),
            new ChatModelResponse(
                "", List.of(new ToolCall("call-2", "lookup", Map.of()))));
    var events = new ArrayList<TurnLifecycleEvent>();
    var service =
        service(repository, model, List.of(tool("lookup", "结果", new ArrayList<>())), 2, events::add);

    assertThatThrownBy(() -> service.chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(ToolLoopLimitExceededException.class)
        .hasMessageNotContaining("call-2");

    assertThat(repository.appended).isEmpty();
    assertThat(events.getLast().type().name()).isEqualTo("TURN_FAILED");
    assertThat(events.getLast().status()).isEqualTo("TOOL_LOOP_LIMIT_EXCEEDED");
  }

  @Test
  void rejectsInvalidModelResponseWithoutCommitAndIsolatesObserverFailure() {
    var repository = new RecordingRepository();
    ChatModelPort invalid = request -> null;
    var service =
        service(
            repository,
            invalid,
            List.of(),
            2,
            event -> {
              throw new IllegalStateException("observer must be isolated");
            });

    assertThatThrownBy(() -> service.chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(InvalidModelResponseException.class);
    assertThat(repository.appended).isEmpty();
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      List<Tool> tools,
      int maxIterations,
      io.namei.agent.kernel.port.TurnLifecycleObserver observer) {
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000),
        directGate(),
        "系统提示",
        CLOCK,
        tools,
        maxIterations,
        observer);
  }

  private static Tool tool(String name, String result, List<String> executionOrder) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return ToolLoopTest.definition(name);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        executionOrder.add(name);
        return ToolResult.success(result);
      }
    };
  }

  private static ToolDefinition definition(String name) {
    return new ToolDefinition(
        name,
        "测试工具 " + name,
        Map.of("type", "object", "properties", Map.of()),
        ToolRisk.READ_ONLY);
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
}
