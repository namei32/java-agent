package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ToolRuntimeSafetyTest {
  @Test
  void rejectsOversizedBatchBeforeExecutingAnyTool() {
    var executions = new ArrayList<String>();
    var model =
        new ScriptedModel(
            response(call("call-1", "lookup", Map.of()), call("call-2", "lookup", Map.of())));
    var repository = new RecordingRepository();
    var events = new ArrayList<TurnLifecycleEvent>();
    var settings = settings(1, 2, 20_000);

    assertThatThrownBy(
            () ->
                service(
                        repository,
                        model,
                        List.of(tool("lookup", objectSchema(), "结果", executions)),
                        settings,
                        events)
                    .chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(ToolCallLimitExceededException.class);

    assertThat(executions).isEmpty();
    assertThat(repository.appended).isEmpty();
    assertThat(events.getLast().status()).isEqualTo("TOOL_CALL_LIMIT_EXCEEDED");
  }

  @Test
  void rejectsBatchThatWouldExceedCumulativeTurnBudget() {
    var executions = new ArrayList<String>();
    var model =
        new ScriptedModel(
            response(call("call-1", "lookup", Map.of()), call("call-2", "lookup", Map.of())),
            response(call("call-3", "lookup", Map.of())));
    var repository = new RecordingRepository();

    assertThatThrownBy(
            () ->
                service(
                        repository,
                        model,
                        List.of(tool("lookup", objectSchema(), "结果", executions)),
                        settings(2, 2, 20_000),
                        new ArrayList<>())
                    .chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(ToolCallLimitExceededException.class);

    assertThat(executions).containsExactly("lookup", "lookup");
    assertThat(repository.appended).isEmpty();
  }

  @Test
  void validatesWholeBatchBeforeReturningSafeArgumentErrors() {
    var executions = new ArrayList<String>();
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of(
                "query", Map.of("type", "string", "enum", List.of("allowed")),
                "count", Map.of("type", "integer")),
            "required",
            List.of("query"),
            "additionalProperties",
            false);
    var model =
        new ScriptedModel(
            response(
                call("missing", "lookup", Map.of()),
                call("type", "lookup", Map.of("query", 1)),
                call("enum", "lookup", Map.of("query", "denied")),
                call("unknown", "lookup", Map.of("query", "allowed", "extra", true))),
            new ChatModelResponse("已安全处理"));

    service(
            new RecordingRepository(),
            model,
            List.of(tool("lookup", schema, "不应执行", executions)),
            settings(8, 16, 20_000),
            new ArrayList<>())
        .chat(new ChatCommand("demo", "问题"));

    assertThat(executions).isEmpty();
    assertThat(model.requests.get(1).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(message -> (ToolResultMessage) message)
        .allSatisfy(
            result -> {
              assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
              assertThat(result.content()).isEqualTo("工具参数无效。");
            });
  }

  @Test
  void rejectsUnsupportedSchemaAtRegistration() {
    var schema =
        Map.<String, Object>of("type", "object", "properties", Map.of(), "unsupported", true);

    assertThatThrownBy(
            () ->
                service(
                    new RecordingRepository(),
                    request -> new ChatModelResponse("回答"),
                    List.of(tool("lookup", schema, "结果", new ArrayList<>())),
                    settings(8, 16, 20_000),
                    new ArrayList<>()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Schema");
  }

  @Test
  void replacesOversizedUnicodeResultWithoutReturningPartialContent() {
    var model =
        new ScriptedModel(
            response(call("call-1", "lookup", Map.of())), new ChatModelResponse("替代回答"));

    service(
            new RecordingRepository(),
            model,
            List.of(tool("lookup", objectSchema(), "😀😀😀", new ArrayList<>())),
            settings(8, 16, 2),
            new ArrayList<>())
        .chat(new ChatCommand("demo", "问题"));

    assertThat(model.requests.get(1).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(message -> (ToolResultMessage) message)
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
              assertThat(result.content()).isEqualTo("工具结果超过大小限制。");
              assertThat(result.content()).doesNotContain("😀");
            });
  }

  private static ToolRuntimeSettings settings(
      int maxPerResponse, int maxPerTurn, int maxResultCharacters) {
    return new ToolRuntimeSettings(
        ToolRuntimeMode.READ_ONLY,
        maxPerResponse,
        maxPerTurn,
        Duration.ofSeconds(5),
        32,
        maxResultCharacters);
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      List<Tool> tools,
      ToolRuntimeSettings settings,
      List<TurnLifecycleEvent> events) {
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000),
        directGate(),
        "系统提示",
        Clock.systemUTC(),
        tools,
        6,
        events::add,
        settings);
  }

  private static Tool tool(
      String name, Map<String, Object> schema, String result, List<String> executions) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(name, "测试工具", schema, ToolRisk.READ_ONLY);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        executions.add(name);
        return ToolResult.success(result);
      }
    };
  }

  private static Map<String, Object> objectSchema() {
    return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
  }

  private static ChatModelResponse response(ToolCall... calls) {
    return new ChatModelResponse("", List.of(calls));
  }

  private static ToolCall call(String id, String name, Map<String, Object> arguments) {
    return new ToolCall(id, name, arguments);
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
