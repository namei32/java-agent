package io.namei.agent.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.error.TurnCancelledException;
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
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ToolRuntimeSafetyGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executesRuntimeSafetyGoldenAgainstProductionRuntime() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/runtime-safety.json"));

    assertThat(fixture.path("source").asString()).isEqualTo("migration-contract");
    var identifiers = new HashSet<String>();
    for (JsonNode testCase : fixture.path("cases")) {
      assertThat(identifiers.add(testCase.path("id").asString())).isTrue();
      if (testCase.path("target").asString().equals("ADAPTER")) {
        continue;
      }

      var actual =
          testCase.path("target").asString().equals("REGISTRY_CONCURRENCY")
              ? executePermitTimeout(testCase.path("input"))
              : executeApplication(testCase.path("input"));
      JsonNode expected = testCase.path("expected");

      assertThat(actual.outcome())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("outcome").asString());
      assertThat(actual.assistant())
          .isEqualTo(
              expected.path("assistant").isNull() ? null : expected.path("assistant").asString());
      assertThat(actual.committed()).isEqualTo(expected.path("committed").asBoolean());
      assertThat(actual.executions())
          .containsExactlyElementsOf(strings(expected.path("executions")));
      assertThat(actual.definitionCounts())
          .containsExactlyElementsOf(integers(expected.path("definitionCounts")));
      assertThat(results(actual.toolResults())).isEqualTo(expected.path("toolResults"));
      assertThat(trace(actual.events())).isEqualTo(expected.path("trace"));
    }

    assertThat(identifiers)
        .containsExactlyInAnyOrder(
            "response-call-limit",
            "turn-call-limit",
            "arguments-byte-limit",
            "schema-argument-errors",
            "result-character-limit",
            "tool-timeout-recovers",
            "permit-wait-timeout",
            "cancel-active-tool",
            "disabled-sends-no-definitions");
  }

  private static Actual executeApplication(JsonNode input) {
    var repository = new RecordingRepository();
    var events = new ArrayList<TurnLifecycleEvent>();
    var executions = new ArrayList<String>();
    var cancellation = new TurnCancellationSource();
    var model = new GoldenModel(input.path("modelResponses"));
    var service =
        new ChatService(
            repository,
            model,
            new ConversationHistorySelector(),
            new HistoryLimits(40, 100_000),
            directGate(),
            "系统提示",
            Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC),
            tools(input.path("tools"), executions, cancellation),
            6,
            events::add,
            settings(input.path("settings")));

    String outcome;
    String assistant = null;
    try {
      assistant =
          service.chat(new ChatCommand("golden", "问题"), cancellation.token()).assistant().content();
      outcome = "COMPLETED";
    } catch (ToolCallLimitExceededException exception) {
      outcome = "TOOL_CALL_LIMIT_EXCEEDED";
    } catch (TurnCancelledException exception) {
      outcome = "TURN_CANCELLED";
    } catch (InvalidModelResponseException exception) {
      outcome = "INVALID_MODEL_RESPONSE";
    } catch (ToolLoopLimitExceededException exception) {
      outcome = "TOOL_LOOP_LIMIT_EXCEEDED";
    }

    return new Actual(
        outcome,
        assistant,
        !repository.appended.isEmpty(),
        executions,
        toolResults(model.requests),
        model.requests.stream().map(request -> request.tools().size()).toList(),
        events);
  }

  private static Actual executePermitTimeout(JsonNode input) throws Exception {
    var executions = new ArrayList<String>();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    Tool tool =
        tool(
            "limited",
            objectSchema(),
            arguments -> {
              executions.add("limited");
              started.countDown();
              boolean done = false;
              while (!done) {
                try {
                  done = release.await(1, SECONDS);
                } catch (InterruptedException ignored) {
                  // 模拟不响应中断的缺陷工具，验证许可只能在实际退出时释放。
                }
              }
              return ToolResult.success("完成");
            });
    var registry = new ToolRegistry(List.of(tool), settings(input.path("settings")));
    var resultValues = new ArrayList<ToolResult>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(() -> registry.execute(new ToolCall("permit-1", "limited", Map.of())));
      assertThat(started.await(1, SECONDS)).isTrue();
      resultValues.add(registry.execute(new ToolCall("permit-2", "limited", Map.of())));
      release.countDown();
      resultValues.addFirst(first.get(1, SECONDS));
    }
    return new Actual(
        "PERMIT_TIMEOUT", null, false, executions, resultValues, List.of(), List.of());
  }

  private static List<Tool> tools(
      JsonNode configuredTools, List<String> executions, TurnCancellationSource cancellation) {
    var result = new ArrayList<Tool>();
    for (JsonNode configured : configuredTools) {
      Map<String, Object> schema =
          JSON.convertValue(configured.path("schema"), new TypeReference<Map<String, Object>>() {});
      result.add(
          tool(
              configured.path("name").asString(),
              schema,
              arguments -> {
                executions.add(configured.path("name").asString());
                return switch (configured.path("behavior").asString()) {
                  case "ERROR" -> throw new IllegalStateException("内部异常不得暴露");
                  case "TIMEOUT" -> waitUntilInterrupted();
                  case "CANCEL" -> {
                    cancellation.cancel();
                    yield ToolResult.success("不应暴露");
                  }
                  default -> ToolResult.success(configured.path("result").asString());
                };
              }));
    }
    return result;
  }

  private static Tool tool(
      String name,
      Map<String, Object> schema,
      java.util.function.Function<Map<String, Object>, ToolResult> action) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(name, "Golden 安全工具", schema, ToolRisk.READ_ONLY);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        return action.apply(arguments);
      }
    };
  }

  private static ToolResult waitUntilInterrupted() {
    try {
      new CountDownLatch(1).await();
      return ToolResult.success("不应完成");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return ToolResult.error("不应暴露");
    }
  }

  private static ToolRuntimeSettings settings(JsonNode configured) {
    return new ToolRuntimeSettings(
        ToolRuntimeMode.valueOf(configured.path("mode").asString()),
        configured.path("maxCallsPerResponse").asInt(),
        configured.path("maxCallsPerTurn").asInt(),
        Duration.ofMillis(configured.path("timeoutMillis").asLong()),
        configured.path("maxConcurrentCalls").asInt(),
        configured.path("maxResultCharacters").asInt());
  }

  private static List<ToolResult> toolResults(List<ChatModelRequest> requests) {
    if (requests.isEmpty()) {
      return List.of();
    }
    return requests.getLast().messages().stream()
        .filter(ToolResultMessage.class::isInstance)
        .map(ToolResultMessage.class::cast)
        .map(message -> new ToolResult(message.status(), message.content()))
        .toList();
  }

  private static JsonNode results(List<ToolResult> values) {
    var result = JSON.createArrayNode();
    for (ToolResult value : values) {
      var node = result.addObject();
      node.put("content", value.content());
      node.put("status", value.status().name());
    }
    return result;
  }

  private static JsonNode trace(List<TurnLifecycleEvent> events) {
    var result = JSON.createArrayNode();
    for (TurnLifecycleEvent event : events) {
      var node = result.addObject();
      if (!event.callId().isEmpty()) {
        node.put("callId", event.callId());
      }
      node.put("iteration", event.iteration());
      if (!event.status().isEmpty()) {
        node.put("status", event.status());
      }
      if (!event.toolName().isEmpty()) {
        node.put("toolName", event.toolName());
      }
      node.put("type", event.type().name());
    }
    return result;
  }

  private static List<String> strings(JsonNode array) {
    var result = new ArrayList<String>();
    array.forEach(value -> result.add(value.asString()));
    return result;
  }

  private static List<Integer> integers(JsonNode array) {
    var result = new ArrayList<Integer>();
    array.forEach(value -> result.add(value.asInt()));
    return result;
  }

  private static Map<String, Object> objectSchema() {
    return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
  }

  private static SessionExecutionGate directGate() {
    return new SessionExecutionGate() {
      @Override
      public <T> T execute(String sessionId, Supplier<T> action) {
        return action.get();
      }
    };
  }

  private record Actual(
      String outcome,
      String assistant,
      boolean committed,
      List<String> executions,
      List<ToolResult> toolResults,
      List<Integer> definitionCounts,
      List<TurnLifecycleEvent> events) {}

  private static final class GoldenModel implements ChatModelPort {
    private final ArrayDeque<JsonNode> responses = new ArrayDeque<>();
    private final List<ChatModelRequest> requests = new ArrayList<>();

    private GoldenModel(JsonNode responses) {
      responses.forEach(this.responses::addLast);
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      JsonNode response = responses.removeFirst();
      var calls = new ArrayList<ToolCall>();
      for (JsonNode call : response.path("toolCalls")) {
        Map<String, Object> arguments =
            JSON.convertValue(call.path("arguments"), new TypeReference<Map<String, Object>>() {});
        calls.add(
            new ToolCall(call.path("id").asString(), call.path("name").asString(), arguments));
      }
      String content =
          response.path("content").isNull() ? null : response.path("content").asString();
      return new ChatModelResponse(content, calls);
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

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
