package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolRuntimeModeTest {
  @Test
  void disabledModeSendsNoDefinitionsAndKeepsDirectChatAvailable() {
    var request = new AtomicReference<ChatModelRequest>();
    ChatModelPort model =
        value -> {
          request.set(value);
          return new ChatModelResponse("普通回答");
        };
    var repository = new RecordingRepository();

    var result =
        service(repository, model, ToolRuntimeSettings.disabled(), new ArrayList<>())
            .chat(new ChatCommand("demo", "问题"));

    assertThat(result.assistant().content()).isEqualTo("普通回答");
    assertThat(request.get().tools()).isEmpty();
    assertThat(repository.appended).hasSize(1);
  }

  @Test
  void disabledModeRejectsUnexpectedProviderToolCallsWithoutExecutionOrCommit() {
    var executions = new ArrayList<String>();
    ChatModelPort model =
        request ->
            new ChatModelResponse(
                "", List.of(new ToolCall("call-1", "current_time", Map.of())));
    var repository = new RecordingRepository();

    assertThatThrownBy(
            () ->
                service(repository, model, ToolRuntimeSettings.disabled(), executions)
                    .chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(InvalidModelResponseException.class);

    assertThat(executions).isEmpty();
    assertThat(repository.appended).isEmpty();
  }

  @Test
  void settingsRejectInvalidBudgets() {
    assertThatThrownBy(
            () ->
                new ToolRuntimeSettings(
                    ToolRuntimeMode.READ_ONLY, 0, 16, Duration.ofSeconds(5), 32, 20_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ToolRuntimeSettings(
                    ToolRuntimeMode.READ_ONLY, 8, 7, Duration.ofSeconds(5), 32, 20_000))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      ToolRuntimeSettings settings,
      List<String> executions) {
    Tool configured =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return new ToolDefinition(
                "current_time",
                "测试工具",
                Map.of("type", "object", "properties", Map.of()),
                ToolRisk.READ_ONLY);
          }

          @Override
          public ToolResult execute(Map<String, Object> arguments) {
            executions.add("current_time");
            return ToolResult.success("不应执行");
          }
        };
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000),
        new KeyedSessionExecutionGate(Duration.ofSeconds(1)),
        "系统提示",
        Clock.systemUTC(),
        List.of(configured),
        6,
        event -> {},
        settings);
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
