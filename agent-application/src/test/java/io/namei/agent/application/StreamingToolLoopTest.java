package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.ChatModelStreamObserver;
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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class StreamingToolLoopTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void streamsAcrossToolIterationsAndCommitsOnlyAuthoritativeFinalText() {
    var repository = new RecordingRepository();
    var model =
        new ScriptedStreamingModel(
            new Step(
                List.of("查询中"),
                new ChatModelResponse("查询中", List.of(new ToolCall("call-1", "lookup", Map.of())))),
            new Step(List.of("晴", "天"), new ChatModelResponse("晴天")));
    var deltas = new ArrayList<String>();
    var service = service(repository, model, List.of(tool("lookup", "结果")), 3);

    var result = service.chat(new ChatCommand("demo", "问题"), TurnCancellation.none(), deltas::add);

    assertThat(deltas).containsExactly("查询中", "晴", "天");
    assertThat(result.assistant().content()).isEqualTo("晴天");
    assertThat(model.requests).hasSize(2);
    assertThat(repository.appended)
        .singleElement()
        .extracting(turn -> turn.assistant().content())
        .isEqualTo("晴天");
  }

  @Test
  void enforcesEveryStreamingBudgetFixtureCaseUsingUnicodeCodePoints() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("message-bus/provider-streaming-cli.json"));
    var settings =
        new ModelStreamingSettings(
            fixture.path("limits").path("maxDeltaEvents").asInt(),
            fixture.path("limits").path("maxDeltaCodePoints").asInt());
    int consumed = 0;

    for (JsonNode testCase : fixture.path("applicationCases")) {
      String caseId = testCase.path("id").asString();
      if (!(caseId.contains("limit") || caseId.equals("empty-delta-is-rejected"))) {
        continue;
      }
      consumed++;
      var budget = new StreamingBudget(settings);
      String outcome;
      try {
        for (JsonNode delta : testCase.path("input").path("deltas")) {
          budget.accept(delta.asString());
        }
        outcome = "COMPLETED";
      } catch (ModelStreamLimitExceededException exception) {
        outcome = "STREAM_LIMIT_EXCEEDED";
      } catch (InvalidModelResponseException exception) {
        outcome = "INVALID_MODEL_STREAM";
      }

      assertThat(outcome)
          .as(caseId)
          .isEqualTo(testCase.path("expected").path("outcome").asString());
    }

    assertThat(consumed).isEqualTo(5);
  }

  @Test
  void preCancellationSkipsProviderAndDoesNotCommit() {
    var repository = new RecordingRepository();
    var model = new ScriptedStreamingModel(new Step(List.of("不应出现"), new ChatModelResponse("回答")));
    var cancellation = new TurnCancellationSource();
    cancellation.cancel();
    var service = service(repository, model, List.of(), 1);

    assertThatThrownBy(
            () -> service.chat(new ChatCommand("demo", "问题"), cancellation.token(), ignored -> {}))
        .isInstanceOf(TurnCancelledException.class);

    assertThat(model.requests).isEmpty();
    assertThat(repository.appended).isEmpty();
  }

  @Test
  void cancellationFromProgressStopsLaterDeltasAndPreventsCommit() {
    var repository = new RecordingRepository();
    var model =
        new ScriptedStreamingModel(
            new Step(List.of("部", "分", "迟到"), new ChatModelResponse("部分迟到")));
    var cancellation = new TurnCancellationSource();
    var deltas = new ArrayList<String>();
    var service = service(repository, model, List.of(), 1);

    assertThatThrownBy(
            () ->
                service.chat(
                    new ChatCommand("demo", "问题"),
                    cancellation.token(),
                    delta -> {
                      deltas.add(delta);
                      cancellation.cancel();
                    }))
        .isInstanceOf(TurnCancelledException.class);

    assertThat(deltas).containsExactly("部");
    assertThat(repository.appended).isEmpty();
  }

  private static ChatService service(
      SessionRepository repository, ChatModelPort model, List<Tool> tools, int maxIterations) {
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
        event -> {});
  }

  private static Tool tool(String name, String result) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(
            name, "测试工具", Map.of("type", "object", "properties", Map.of()), ToolRisk.READ_ONLY);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.success(result);
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

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private record Step(List<String> deltas, ChatModelResponse response) {
    private Step {
      deltas = List.copyOf(deltas);
    }
  }

  private static final class ScriptedStreamingModel implements ChatModelPort {
    private final ArrayDeque<Step> steps;
    private final List<ChatModelRequest> requests = new ArrayList<>();

    private ScriptedStreamingModel(Step... steps) {
      this.steps = new ArrayDeque<>(List.of(steps));
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      throw new AssertionError("流式入口不能退化为同步入口");
    }

    @Override
    public ChatModelResponse generate(
        ChatModelRequest request,
        ChatModelStreamObserver observer,
        CancellationSignal cancellation) {
      requests.add(request);
      Step step = steps.removeFirst();
      for (String delta : step.deltas()) {
        observer.onContentDelta(delta);
      }
      return step.response();
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
