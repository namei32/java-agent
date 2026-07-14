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
            new ChatModelResponse("", List.of(new ToolCall("call-1", "current_time", Map.of())));
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

  @Test
  void readOnlyModeRejectsSideEffectRegistration() {
    assertThatThrownBy(
            () ->
                service(
                    new RecordingRepository(),
                    request -> new ChatModelResponse("回答"),
                    ToolRuntimeSettings.readOnlyDefaults(),
                    new ArrayList<>(),
                    ToolRisk.WRITE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("READ_ONLY");
  }

  @Test
  void approvalRequiredPublishesRegisteredDefinitionButDefaultsToDenyAll() {
    var request = new AtomicReference<ChatModelRequest>();
    var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
    ChatModelPort model =
        value -> {
          request.set(value);
          if (modelCalls.getAndIncrement() == 0) {
            return new ChatModelResponse(
                "", List.of(new ToolCall("call-1", "write_note", Map.of())));
          }
          return new ChatModelResponse("未执行");
        };
    var executions = new ArrayList<String>();
    var settings =
        new ToolRuntimeSettings(
            ToolRuntimeMode.APPROVAL_REQUIRED,
            8,
            16,
            Duration.ofSeconds(5),
            32,
            20_000);

    var result =
        service(
                new RecordingRepository(),
                model,
                settings,
                executions,
                ToolRisk.WRITE)
            .chat(new ChatCommand("demo", "问题"));

    assertThat(result.assistant().content()).isEqualTo("未执行");
    assertThat(executions).isEmpty();
    assertThat(request.get().tools()).extracting(ToolDefinition::name).containsExactly("write_note");
    assertThat(ToolRuntimeMode.valueOf("APPROVAL_REQUIRED"))
        .isEqualTo(ToolRuntimeMode.APPROVAL_REQUIRED);
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      ToolRuntimeSettings settings,
      List<String> executions) {
    return service(repository, model, settings, executions, ToolRisk.READ_ONLY);
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      ToolRuntimeSettings settings,
      List<String> executions,
      ToolRisk risk) {
    String toolName = risk == ToolRisk.READ_ONLY ? "current_time" : "write_note";
    Tool configured =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return new ToolDefinition(
                toolName,
                "测试工具",
                Map.of("type", "object", "properties", Map.of()),
                risk);
          }

          @Override
          public ToolResult execute(Map<String, Object> arguments) {
            executions.add(toolName);
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
