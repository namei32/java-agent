package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
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
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ToolGoldenFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void validatesPythonToolMessageProjection() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/message-envelope.json"));

    assertThat(fixture.path("source").asString()).isEqualTo("python-reference");
    assertThat(fixture.path("cases")).hasSize(2);
    for (JsonNode testCase : fixture.path("cases")) {
      JsonNode input = testCase.path("input");
      JsonNode messages = testCase.path("expected").path("messages");
      assertThat(input.path("toolDefinitions").path(0).path("function").path("name").asString())
          .isEqualTo("golden_lookup");
      assertThat(messages.path(0).path("role").asString()).isEqualTo("assistant");
      assertThat(messages.path(0).path("tool_calls")).hasSize(input.path("toolCalls").size());
      assertThat(messages.size()).isEqualTo(input.path("toolCalls").size() + 1);

      for (int index = 0; index < input.path("toolCalls").size(); index++) {
        JsonNode call = input.path("toolCalls").path(index);
        JsonNode rendered = messages.path(0).path("tool_calls").path(index);
        assertThat(rendered.path("id").asString()).isEqualTo(call.path("id").asString());
        assertThat(rendered.path("function").path("name").asString())
            .isEqualTo(call.path("name").asString());
        assertThat(JSON.readTree(rendered.path("function").path("arguments").asString()))
            .isEqualTo(call.path("arguments"));

        JsonNode result = messages.path(index + 1);
        assertThat(result.path("role").asString()).isEqualTo("tool");
        assertThat(result.path("tool_call_id").asString()).isEqualTo(call.path("id").asString());
      }
    }
  }

  @Test
  void executesApprovedMinimalLoopCasesAgainstProductionCode() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/minimal-loop.json"));

    assertThat(fixture.path("source").asString()).isEqualTo("migration-contract");
    var identifiers = new HashSet<String>();
    var outcomes = new ArrayList<String>();
    for (JsonNode testCase : fixture.path("cases")) {
      assertThat(identifiers.add(testCase.path("id").asString())).isTrue();
      JsonNode expected = testCase.path("expected");
      var actual = execute(testCase.path("input"));
      outcomes.add(actual.outcome());

      assertThat(actual.outcome())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("outcome").asString());
      assertThat(actual.committed()).isEqualTo(expected.path("committed").asBoolean());
      if (expected.path("assistant").isNull()) {
        assertThat(actual.assistant()).isNull();
      } else {
        assertThat(actual.assistant()).isEqualTo(expected.path("assistant").asString());
      }
      assertThat(actual.executionOrder())
          .containsExactlyElementsOf(strings(expected.path("executionOrder")));
      assertThat(trace(actual.events())).isEqualTo(expected.path("trace"));
    }

    assertThat(identifiers)
        .containsExactlyInAnyOrder(
            "direct-answer",
            "single-tool-success",
            "multiple-tools-preserve-order",
            "unknown-tool-recovers",
            "tool-error-recovers",
            "invalid-model-response",
            "iteration-limit-does-not-commit");
    assertThat(outcomes)
        .contains("COMPLETED", "INVALID_MODEL_RESPONSE", "TOOL_LOOP_LIMIT_EXCEEDED");
  }

  private static Actual execute(JsonNode input) {
    var repository = new RecordingRepository();
    var events = new ArrayList<TurnLifecycleEvent>();
    var service =
        new ChatService(
            repository,
            new GoldenModel(input.path("modelResponses")),
            new ConversationHistorySelector(),
            new HistoryLimits(40, 100_000),
            directGate(),
            "系统提示",
            Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC),
            tools(input.path("tools")),
            input.path("maxIterations").asInt(),
            events::add);

    String outcome;
    String assistant = null;
    try {
      assistant = service.chat(new ChatCommand("golden", "问题")).assistant().content();
      outcome = "COMPLETED";
    } catch (InvalidModelResponseException exception) {
      outcome = "INVALID_MODEL_RESPONSE";
    } catch (ToolLoopLimitExceededException exception) {
      outcome = "TOOL_LOOP_LIMIT_EXCEEDED";
    }
    var executionOrder =
        events.stream()
            .filter(event -> event.type().name().equals("TOOL_CALL_STARTED"))
            .map(TurnLifecycleEvent::toolName)
            .toList();
    return new Actual(outcome, assistant, !repository.appended.isEmpty(), executionOrder, events);
  }

  private static List<Tool> tools(JsonNode definitions) {
    var tools = new ArrayList<Tool>();
    for (JsonNode configured : definitions) {
      tools.add(
          new Tool() {
            @Override
            public ToolDefinition definition() {
              return new ToolDefinition(
                  configured.path("name").asString(),
                  "Golden 测试工具",
                  Map.of("type", "object", "properties", Map.of()),
                  ToolRisk.READ_ONLY);
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
              if (configured.path("behavior").asString().equals("ERROR")) {
                throw new IllegalStateException(configured.path("result").asString());
              }
              return ToolResult.success(configured.path("result").asString());
            }
          });
    }
    return tools;
  }

  private static JsonNode trace(List<TurnLifecycleEvent> events) {
    var trace = JSON.createArrayNode();
    for (TurnLifecycleEvent event : events) {
      var node = trace.addObject();
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
    return trace;
  }

  private static List<String> strings(JsonNode array) {
    var values = new ArrayList<String>();
    array.forEach(value -> values.add(value.asString()));
    return values;
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
      List<String> executionOrder,
      List<TurnLifecycleEvent> events) {}

  private static final class GoldenModel implements ChatModelPort {
    private final ArrayDeque<JsonNode> responses = new ArrayDeque<>();

    private GoldenModel(JsonNode responses) {
      responses.forEach(this.responses::addLast);
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
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
      if ((content == null || content.isBlank()) && calls.isEmpty()) {
        return null;
      }
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
