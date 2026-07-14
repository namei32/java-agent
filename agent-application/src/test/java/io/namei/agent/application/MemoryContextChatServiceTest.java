package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.memory.MemoryProfile;
import io.namei.agent.kernel.memory.MemoryRetrievalRequest;
import io.namei.agent.kernel.memory.MemoryRetrievalResult;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.MemoryProfilePort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class MemoryContextChatServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void retrievesWithFullHistoryButInjectsOnlySelectedHistoryAndTemporaryFrame() {
    var fullHistory =
        List.of(
            message(MessageRole.USER, "第一问"),
            message(MessageRole.ASSISTANT, "第一答"),
            message(MessageRole.USER, "第二问"),
            message(MessageRole.ASSISTANT, "第二答"));
    var repository = new RecordingRepository(fullHistory);
    var model = new ScriptedModel(new ChatModelResponse("最终回答"));
    var retrieval = new RecordingRetrieval(MemoryRetrievalResult.retrieved("命中的只读记忆", 1));
    var memory =
        new MemoryContextService(
            () -> new MemoryProfile("稳定身份", "长期偏好", "近期语境"), retrieval, 100_000, 20_000);

    service(repository, model, new HistoryLimits(2, 100_000), List.of(), 1, memory, event -> {})
        .chat(new ChatCommand("demo", "当前问题"));

    assertThat(retrieval.request.history()).containsExactlyElementsOf(fullHistory);
    assertThat(retrieval.request.currentMessage()).isEqualTo("当前问题");
    assertThat(retrieval.request.requestedAt()).isEqualTo(CLOCK.instant());
    assertThat(retrieval.request.sessionBinding()).matches("[0-9a-f]{64}");
    assertThat(model.requests.getFirst().messages())
        .extracting(message -> message.role().name())
        .containsExactly("SYSTEM", "USER", "ASSISTANT", "USER", "USER");
    assertThat(model.requests.getFirst().messages().get(1).content()).isEqualTo("第二问");
    assertThat(model.requests.getFirst().messages().get(3).content())
        .contains("## recent_context\n近期语境")
        .contains("## retrieved_memory\n命中的只读记忆");
    assertThat(model.requests.getFirst().messages().getLast().content()).isEqualTo("当前问题");
    assertThat(repository.appended)
        .singleElement()
        .satisfies(MemoryContextChatServiceTest::assertRealTurnOnly);
  }

  @Test
  void disabledRetrievalSafelyProducesNoContextFrame() {
    var repository = new RecordingRepository(List.of());
    var model = new ScriptedModel(new ChatModelResponse("回答"));
    var memory =
        new MemoryContextService(
            MemoryProfilePort.empty(), MemoryRetrievalPort.disabled(), 100_000, 20_000);

    service(repository, model, new HistoryLimits(40, 100_000), List.of(), 1, memory, event -> {})
        .chat(new ChatCommand("demo", "问题"));

    assertThat(model.requests.getFirst().messages())
        .containsExactly(message(MessageRole.SYSTEM, "基础 Prompt"), message(MessageRole.USER, "问题"));
  }

  @Test
  @Tag("failure")
  void profileRetrievalAndBudgetFailuresStopBeforeModelAndCommitWithStableStatus() {
    assertMemoryFailure(
        () -> {
          throw new IllegalStateException("/secret/profile/path");
        },
        request -> MemoryRetrievalResult.empty());
    assertMemoryFailure(
        MemoryProfilePort.empty(),
        request -> {
          throw new IllegalStateException("sensitive query");
        });
    assertMemoryFailure(
        MemoryProfilePort.empty(), request -> MemoryRetrievalResult.retrieved("12345", 1), 4);
  }

  @Test
  void keepsSameContextFrameAcrossToolLoopAndNeverPersistsIt() {
    var repository = new RecordingRepository(List.of());
    var model =
        new ScriptedModel(
            new ChatModelResponse("", List.of(new ToolCall("call-1", "lookup", Map.of()))),
            new ChatModelResponse("工具后的最终回答"));
    var memory =
        new MemoryContextService(
            () -> new MemoryProfile("", "", "近期语境"),
            request -> MemoryRetrievalResult.retrieved("检索记忆", 1),
            100_000,
            20_000);

    service(
            repository,
            model,
            new HistoryLimits(40, 100_000),
            List.of(readOnlyTool()),
            2,
            memory,
            event -> {})
        .chat(new ChatCommand("demo", "使用工具"));

    assertThat(model.requests).hasSize(2);
    String frame = model.requests.getFirst().messages().get(1).content();
    assertThat(frame).contains("近期语境").contains("检索记忆");
    assertThat(model.requests.getLast().messages().get(1).content()).isEqualTo(frame);
    assertThat(model.requests.getLast().messages())
        .anyMatch(AssistantToolCallMessage.class::isInstance)
        .anyMatch(ToolResultMessage.class::isInstance);
    assertThat(repository.appended)
        .singleElement()
        .satisfies(MemoryContextChatServiceTest::assertRealTurnOnly);
  }

  private static void assertMemoryFailure(
      MemoryProfilePort profiles, MemoryRetrievalPort retrieval) {
    assertMemoryFailure(profiles, retrieval, 20_000);
  }

  private static void assertMemoryFailure(
      MemoryProfilePort profiles, MemoryRetrievalPort retrieval, int maxRetrievedCharacters) {
    var repository = new RecordingRepository(List.of());
    var model = new ScriptedModel(new ChatModelResponse("不应调用"));
    var events = new ArrayList<TurnLifecycleEvent>();
    var memory = new MemoryContextService(profiles, retrieval, 100_000, maxRetrievedCharacters);
    var chat =
        service(
            repository, model, new HistoryLimits(40, 100_000), List.of(), 1, memory, events::add);

    assertThatThrownBy(() -> chat.chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(MemoryContextUnavailableException.class)
        .hasMessage("记忆上下文当前不可用")
        .hasMessageNotContaining("secret")
        .hasMessageNotContaining("sensitive")
        .hasMessageNotContaining("12345");
    assertThat(model.requests).isEmpty();
    assertThat(repository.appended).isEmpty();
    assertThat(events.getLast().status()).isEqualTo("MEMORY_CONTEXT_UNAVAILABLE");
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      HistoryLimits limits,
      List<Tool> tools,
      int maxIterations,
      MemoryContextService memory,
      io.namei.agent.kernel.port.TurnLifecycleObserver observer) {
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        limits,
        directGate(),
        "基础 Prompt",
        CLOCK,
        tools,
        maxIterations,
        observer,
        memory);
  }

  private static Tool readOnlyTool() {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(
            "lookup", "只读查询", Map.of("type", "object", "properties", Map.of()), ToolRisk.READ_ONLY);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.success("工具结果");
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

  private static ChatMessage message(MessageRole role, String content) {
    return new ChatMessage(role, content);
  }

  private static void assertRealTurnOnly(PersistedTurn turn) {
    assertThat(turn.user()).isEqualTo(message(MessageRole.USER, turn.user().content()));
    assertThat(turn.user().content()).doesNotContain("system-context-frame", "近期语境", "检索记忆");
    assertThat(turn.assistant().role()).isEqualTo(MessageRole.ASSISTANT);
  }

  private static final class RecordingRetrieval implements MemoryRetrievalPort {
    private final MemoryRetrievalResult result;
    private MemoryRetrievalRequest request;

    private RecordingRetrieval(MemoryRetrievalResult result) {
      this.result = result;
    }

    @Override
    public MemoryRetrievalResult retrieve(MemoryRetrievalRequest request) {
      this.request = request;
      return result;
    }
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
    private final List<ChatMessage> history;
    private final List<PersistedTurn> appended = new ArrayList<>();

    private RecordingRepository(List<ChatMessage> history) {
      this.history = history;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, history, history.size());
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      appended.add(turn);
    }
  }
}
