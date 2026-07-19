package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.ProviderCacheUsage;
import io.namei.agent.kernel.model.ProviderTurnUsage;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.ProviderUsageObserver;
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
import org.junit.jupiter.api.Test;

class ProviderUsageObservationChatServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void publishesOnlyTheAggregateAfterTheSessionTurnCommits() {
    var observations = new ArrayList<ProviderTurnUsage>();
    var repository = new RecordingRepository(false);
    var model =
        new ScriptedModel(new ChatModelResponse("回答", List.of(), new ProviderCacheUsage(10, 4)));

    var result = service(repository, model, List.of(), observations::add).chat(command());

    assertThat(result.assistant().content()).isEqualTo("回答");
    assertThat(repository.appended).hasSize(1);
    assertThat(observations).containsExactly(new ProviderTurnUsage(1, 10L, 4L));
  }

  @Test
  void aggregatesResponsesBeforeAndAfterAReadOnlyToolIntoOneCommittedObservation() {
    var observations = new ArrayList<ProviderTurnUsage>();
    Tool lookup =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return new ToolDefinition(
                "lookup",
                "查询",
                Map.of("type", "object", "properties", Map.of()),
                ToolRisk.READ_ONLY);
          }

          @Override
          public ToolResult execute(Map<String, Object> ignored) {
            return ToolResult.success("结果");
          }
        };
    var model =
        new ScriptedModel(
            new ChatModelResponse(
                "查询中",
                List.of(new ToolCall("call-1", "lookup", Map.of())),
                new ProviderCacheUsage(11, 4)),
            new ChatModelResponse("完成", List.of(), new ProviderCacheUsage(9, 3)));

    service(new RecordingRepository(false), model, List.of(lookup), observations::add)
        .chat(command());

    assertThat(observations).containsExactly(new ProviderTurnUsage(2, 20L, 7L));
  }

  @Test
  void doesNotPublishWhenTurnFailsOrAppendFails() {
    var failedObservations = new ArrayList<ProviderTurnUsage>();
    ChatModelPort failingModel =
        ignored -> {
          throw new IllegalStateException("provider failure");
        };

    assertThatThrownBy(
            () ->
                service(
                        new RecordingRepository(false),
                        failingModel,
                        List.of(),
                        failedObservations::add)
                    .chat(command()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(failedObservations).isEmpty();

    var appendObservations = new ArrayList<ProviderTurnUsage>();
    assertThatThrownBy(
            () ->
                service(
                        new RecordingRepository(true),
                        new ScriptedModel(
                            new ChatModelResponse("回答", List.of(), new ProviderCacheUsage(8, 1))),
                        List.of(),
                        appendObservations::add)
                    .chat(command()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("append failed");
    assertThat(appendObservations).isEmpty();
  }

  @Test
  void doesNotPublishWhenCancellationWinsBeforeCommit() {
    var observations = new ArrayList<ProviderTurnUsage>();
    var cancellation = new TurnCancellationSource();
    ChatModelPort model =
        ignored -> {
          cancellation.cancel();
          return new ChatModelResponse("回答", List.of(), new ProviderCacheUsage(8, 1));
        };
    var repository = new RecordingRepository(false);

    assertThatThrownBy(
            () ->
                service(repository, model, List.of(), observations::add)
                    .chat(command(), cancellation.token()))
        .isInstanceOf(TurnCancelledException.class);

    assertThat(repository.appended).isEmpty();
    assertThat(observations).isEmpty();
  }

  @Test
  void isolatesObserverFailureAfterTheCommittedTurn() {
    var repository = new RecordingRepository(false);
    ProviderUsageObserver failingObserver =
        usage -> {
          throw new IllegalStateException("observer failure");
        };

    var result =
        service(
                repository,
                new ScriptedModel(
                    new ChatModelResponse("回答", List.of(), new ProviderCacheUsage(8, 1))),
                List.of(),
                failingObserver)
            .chat(command());

    assertThat(result.assistant().content()).isEqualTo("回答");
    assertThat(repository.appended).hasSize(1);
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      List<Tool> tools,
      ProviderUsageObserver observer) {
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
        ContextLimitRecoveryPolicy.disabled(),
        observer);
  }

  private static ChatCommand command() {
    return new ChatCommand("demo", "问题");
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
    private final ArrayDeque<ChatModelResponse> responses;

    private ScriptedModel(ChatModelResponse... responses) {
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest ignored) {
      return responses.removeFirst();
    }
  }

  private static final class RecordingRepository implements SessionRepository {
    private final boolean failAppend;
    private final List<PersistedTurn> appended = new ArrayList<>();

    private RecordingRepository(boolean failAppend) {
      this.failAppend = failAppend;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 0);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      if (failAppend) {
        throw new IllegalStateException("append failed");
      }
      appended.add(turn);
    }
  }
}
