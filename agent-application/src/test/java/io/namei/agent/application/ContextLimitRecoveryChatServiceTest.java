package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.ModelContextLimitException;
import io.namei.agent.kernel.error.ModelSafetyRejectedException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
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
import java.time.Clock;
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

class ContextLimitRecoveryChatServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void safeLocalRetriesBeforeAnyToolAndCommitsOnlyTheCurrentTurnWithoutChangingHistory() {
    var history = history();
    var repository = new RecordingRepository(history);
    var model =
        new ScriptedModel(
            new ModelContextLimitException(new IllegalStateException("provider-secret")),
            new ChatModelResponse("恢复后的回答"));

    ChatResult result =
        service(repository, model, List.of(), ContextLimitRecoveryMode.SAFE_LOCAL)
            .chat(new ChatCommand("demo", "当前问题"));

    assertThat(result.assistant().content()).isEqualTo("恢复后的回答");
    assertThat(model.requests).hasSize(2);
    assertThat(model.requests.get(0).messages())
        .contains(new ChatMessage(MessageRole.USER, "当前问题"));
    assertThat(model.requests.get(1).messages())
        .contains(new ChatMessage(MessageRole.USER, "当前问题"));
    assertThat(repository.history).containsExactlyElementsOf(history);
    assertThat(repository.appended)
        .singleElement()
        .satisfies(
            turn -> {
              assertThat(turn.user().content()).isEqualTo("当前问题");
              assertThat(turn.assistant().content()).isEqualTo("恢复后的回答");
            });
  }

  @Test
  @Tag("failure")
  void disabledModeDoesNotIssueASecondRequest() {
    var repository = new RecordingRepository(history());
    var model =
        new ScriptedModel(
            new ModelContextLimitException(new IllegalStateException("provider-secret")));

    assertThatThrownBy(
            () ->
                service(repository, model, List.of(), ContextLimitRecoveryMode.DISABLED)
                    .chat(new ChatCommand("demo", "当前问题")))
        .isInstanceOf(ModelContextLimitException.class)
        .hasMessage("模型上下文超出限制");

    assertThat(model.requests).hasSize(1);
    assertThat(repository.appended).isEmpty();
  }

  @Test
  @Tag("failure")
  void exhaustsBoundedCandidatesWithoutCommittingOrLeakingProviderFailure() {
    var repository = new RecordingRepository(history());
    var attempts = new ArrayList<Object>();
    for (int index = 0; index < 7; index++) {
      attempts.add(
          new ModelContextLimitException(new IllegalStateException("provider-secret-" + index)));
    }
    var model = new ScriptedModel(attempts.toArray());

    assertThatThrownBy(
            () ->
                service(repository, model, List.of(), ContextLimitRecoveryMode.SAFE_LOCAL)
                    .chat(new ChatCommand("demo", "当前问题")))
        .isInstanceOf(ModelContextLimitException.class)
        .hasMessage("模型上下文超出限制")
        .hasMessageNotContaining("provider-secret");

    assertThat(model.requests).hasSize(7);
    assertThat(model.requests.getLast().messages())
        .contains(new ChatMessage(MessageRole.USER, "当前问题"))
        .noneMatch(
            message ->
                message instanceof ChatMessage chat
                    && (chat.content().equals("第一问") || chat.content().equals("第二问")));
    assertThat(repository.appended).isEmpty();
  }

  @Test
  @Tag("failure")
  void doesNotRetryAContextFailureAfterAToolHasExecuted() {
    var repository = new RecordingRepository(history());
    var toolCalls = new AtomicInteger();
    Tool lookup =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return new ToolDefinition(
                "lookup",
                "测试查询",
                Map.of("type", "object", "properties", Map.of()),
                ToolRisk.READ_ONLY);
          }

          @Override
          public ToolResult execute(Map<String, Object> ignored) {
            toolCalls.incrementAndGet();
            return ToolResult.success("结果");
          }
        };
    var model =
        new ScriptedModel(
            new ChatModelResponse("查询", List.of(new ToolCall("call-1", "lookup", Map.of()))),
            new ModelContextLimitException(new IllegalStateException("provider-secret")));

    assertThatThrownBy(
            () ->
                service(repository, model, List.of(lookup), ContextLimitRecoveryMode.SAFE_LOCAL)
                    .chat(new ChatCommand("demo", "当前问题")))
        .isInstanceOf(ModelContextLimitException.class);

    assertThat(toolCalls).hasValue(1);
    assertThat(model.requests).hasSize(2);
    assertThat(repository.appended).isEmpty();
  }

  @Test
  @Tag("failure")
  void doesNotRetryStreamingOrOtherProviderFailures() {
    var repository = new RecordingRepository(history());
    var streamModel = new StreamingContextLimitModel();

    assertThatThrownBy(
            () ->
                service(repository, streamModel, List.of(), ContextLimitRecoveryMode.SAFE_LOCAL)
                    .chat(new ChatCommand("demo", "当前问题"), TurnCancellation.none(), ignored -> {}))
        .isInstanceOf(ModelContextLimitException.class);
    assertThat(streamModel.streamingRequests).hasValue(1);
    assertThat(repository.appended).isEmpty();

    var safetyModel =
        new ScriptedModel(
            new ModelSafetyRejectedException(new IllegalStateException("provider-secret")));
    assertThatThrownBy(
            () ->
                service(repository, safetyModel, List.of(), ContextLimitRecoveryMode.SAFE_LOCAL)
                    .chat(new ChatCommand("demo", "当前问题")))
        .isInstanceOf(ModelSafetyRejectedException.class);
    assertThat(safetyModel.requests).hasSize(1);
  }

  @Test
  @Tag("failure")
  void cancellationAfterTheFirstContextFailurePreventsTheNextCandidate() {
    var repository = new RecordingRepository(history());
    var cancellation = new TurnCancellationSource();
    ChatModelPort model =
        request -> {
          cancellation.cancel();
          throw new ModelContextLimitException(new IllegalStateException("provider-secret"));
        };

    assertThatThrownBy(
            () ->
                service(repository, model, List.of(), ContextLimitRecoveryMode.SAFE_LOCAL)
                    .chat(new ChatCommand("demo", "当前问题"), cancellation.token()))
        .isInstanceOf(TurnCancelledException.class);
    assertThat(repository.appended).isEmpty();
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      List<Tool> tools,
      ContextLimitRecoveryMode recoveryMode) {
    return new ChatService(
        repository,
        model,
        new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000),
        directGate(),
        "系统提示",
        CLOCK,
        tools,
        4,
        ignored -> {},
        new ContextLimitRecoveryPolicy(recoveryMode));
  }

  private static List<ChatMessage> history() {
    return List.of(
        new ChatMessage(MessageRole.USER, "第一问"),
        new ChatMessage(MessageRole.ASSISTANT, "第一答"),
        new ChatMessage(MessageRole.USER, "第二问"),
        new ChatMessage(MessageRole.ASSISTANT, "第二答"));
  }

  private static SessionExecutionGate directGate() {
    return new SessionExecutionGate() {
      @Override
      public <T> T execute(String ignored, Supplier<T> action) {
        return action.get();
      }
    };
  }

  private static final class ScriptedModel implements ChatModelPort {
    private final ArrayDeque<Object> outcomes;
    private final List<ChatModelRequest> requests = new ArrayList<>();

    private ScriptedModel(Object... outcomes) {
      this.outcomes = new ArrayDeque<>(List.of(outcomes));
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      Object outcome = outcomes.removeFirst();
      if (outcome instanceof RuntimeException failure) {
        throw failure;
      }
      return (ChatModelResponse) outcome;
    }
  }

  private static final class StreamingContextLimitModel implements ChatModelPort {
    private final AtomicInteger streamingRequests = new AtomicInteger();

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      throw new AssertionError("流式入口不能调用同步模型");
    }

    @Override
    public ChatModelResponse generate(
        ChatModelRequest request,
        ChatModelStreamObserver observer,
        io.namei.agent.kernel.concurrent.CancellationSignal cancellation) {
      streamingRequests.incrementAndGet();
      throw new ModelContextLimitException(new IllegalStateException("provider-secret"));
    }
  }

  private static final class RecordingRepository implements SessionRepository {
    private final List<ChatMessage> history;
    private final List<PersistedTurn> appended = new ArrayList<>();

    private RecordingRepository(List<ChatMessage> history) {
      this.history = new ArrayList<>(history);
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
