package io.namei.agent.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.lifecycle.TurnEventType;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("failure")
class ToolRuntimeConcurrencyCancellationTest {
  @Test
  void timesOutActiveToolInterruptsItAndContinuesModelLoop() throws Exception {
    var started = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var model = new ScriptedModel(toolResponse("call-1"), new ChatModelResponse("已从超时中恢复"));
    var repository = new RecordingRepository();
    var events = new ArrayList<TurnLifecycleEvent>();

    var result =
        service(
                repository,
                model,
                blockingTool(started, interrupted),
                settings(Duration.ofMillis(100), 1),
                events)
            .chat(new ChatCommand("demo", "问题"));

    assertThat(result.assistant().content()).isEqualTo("已从超时中恢复");
    assertThat(interrupted.await(1, SECONDS)).isTrue();
    assertThat(model.requests.get(1).messages())
        .filteredOn(ToolResultMessage.class::isInstance)
        .extracting(message -> (ToolResultMessage) message)
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.status()).isEqualTo(ToolResultStatus.TIMEOUT);
              assertThat(message.content()).isEqualTo("工具执行超时。");
            });
    assertThat(repository.appended).hasSize(1);
  }

  @Test
  void timesOutWhileWaitingForSharedFairExecutionPermit() throws Exception {
    var firstStarted = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondExecutions = new AtomicInteger();
    var registry =
        new ToolRegistry(
            List.of(limitedTool(firstStarted, releaseFirst, secondExecutions)),
            settings(Duration.ofMillis(100), 1));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> registry.execute(new ToolCall("call-1", "limited", Map.of("order", "first"))));
      assertThat(firstStarted.await(1, SECONDS)).isTrue();

      var second = registry.execute(new ToolCall("call-2", "limited", Map.of("order", "second")));

      assertThat(second.status()).isEqualTo(ToolResultStatus.TIMEOUT);
      assertThat(second.content()).isEqualTo("工具执行超时。");
      assertThat(secondExecutions).hasValue(0);
      releaseFirst.countDown();
      assertThat(first.get(1, SECONDS)).isNotNull();
    }
  }

  @Test
  void cancelsActiveToolEmitsStableEventsAndDoesNotCommit() throws Exception {
    var started = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var source = new TurnCancellationSource();
    var repository = new RecordingRepository();
    var events = new ArrayList<TurnLifecycleEvent>();
    var chat =
        service(
            repository,
            new ScriptedModel(toolResponse("call-1")),
            blockingTool(started, interrupted),
            settings(Duration.ofSeconds(2), 1),
            events);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var turn = executor.submit(() -> chat.chat(new ChatCommand("demo", "问题"), source.token()));
      assertThat(started.await(1, SECONDS)).isTrue();

      source.cancel();

      assertThatThrownBy(() -> turn.get(1, SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(TurnCancelledException.class);
      assertThat(interrupted.await(1, SECONDS)).isTrue();
    }

    assertThat(repository.appended).isEmpty();
    assertThat(events)
        .filteredOn(event -> event.type() == TurnEventType.TOOL_CALL_COMPLETED)
        .extracting(TurnLifecycleEvent::status)
        .containsExactly("CANCELLED");
    assertThat(events.getLast().type()).isEqualTo(TurnEventType.TURN_FAILED);
    assertThat(events.getLast().status()).isEqualTo("TURN_CANCELLED");
  }

  @Test
  void observesCancellationAfterSynchronousModelReturnsBeforeExecutingTools() {
    var source = new TurnCancellationSource();
    var executions = new AtomicInteger();
    var repository = new RecordingRepository();
    ChatModelPort model =
        request -> {
          source.cancel();
          return toolResponse("call-1");
        };

    assertThatThrownBy(
            () ->
                service(
                        repository,
                        model,
                        countingTool(executions),
                        settings(Duration.ofSeconds(1), 1),
                        new ArrayList<>())
                    .chat(new ChatCommand("demo", "问题"), source.token()))
        .isInstanceOf(TurnCancelledException.class);

    assertThat(executions).hasValue(0);
    assertThat(repository.appended).isEmpty();
  }

  @Test
  void isolatesCancellationCallbackFailuresAndNotifiesEveryListener() {
    var source = new TurnCancellationSource();
    var notifications = new AtomicInteger();
    source
        .token()
        .onCancellation(
            () -> {
              throw new IllegalStateException("private");
            });
    source.token().onCancellation(notifications::incrementAndGet);

    source.cancel();

    assertThat(source.token().isCancellationRequested()).isTrue();
    assertThat(notifications).hasValue(1);
  }

  @Test
  void releasesPermitWhenCancelledBeforeToolTaskBodyStarts() throws Exception {
    var source = new TurnCancellationSource();
    var executions = new AtomicInteger();
    var starter = new FirstTaskPausedStarter();
    var registry =
        new ToolRegistry(
            List.of(countingTool(executions)), settings(Duration.ofMillis(200), 1), starter);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> registry.execute(new ToolCall("call-1", "blocking", Map.of()), source.token()));
      assertThat(starter.firstSubmitted.await(1, SECONDS)).isTrue();

      source.cancel();

      assertThat(first.get(1, SECONDS).status()).isEqualTo(ToolResultStatus.CANCELLED);
      assertThat(executions).hasValue(0);
      starter.runFirstTask();
      assertThat(registry.execute(new ToolCall("call-2", "blocking", Map.of())).status())
          .isEqualTo(ToolResultStatus.SUCCESS);
      assertThat(executions).hasValue(1);
    }
  }

  @Test
  void releasesPermitAndReturnsSafeErrorWhenTaskStarterFails() {
    var starts = new AtomicInteger();
    ToolRegistry.ToolTaskStarter starter =
        (toolName, task) -> {
          if (starts.incrementAndGet() == 1) {
            throw new IllegalStateException("private thread start failure");
          }
          Thread.ofVirtual().name("test-tool-" + toolName).start(task);
        };
    var registry =
        new ToolRegistry(
            List.of(countingTool(new AtomicInteger())),
            settings(Duration.ofMillis(200), 1),
            starter);

    var failed = registry.execute(new ToolCall("call-1", "blocking", Map.of()));
    var recovered = registry.execute(new ToolCall("call-2", "blocking", Map.of()));

    assertThat(failed.status()).isEqualTo(ToolResultStatus.ERROR);
    assertThat(failed.content()).isEqualTo("工具执行失败。");
    assertThat(recovered.status()).isEqualTo(ToolResultStatus.SUCCESS);
  }

  private static ChatService service(
      SessionRepository repository,
      ChatModelPort model,
      Tool tool,
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
        List.of(tool),
        6,
        events::add,
        settings);
  }

  private static ToolRuntimeSettings settings(Duration timeout, int concurrentCalls) {
    return new ToolRuntimeSettings(
        ToolRuntimeMode.READ_ONLY, 8, 16, timeout, concurrentCalls, 20_000);
  }

  private static Tool blockingTool(CountDownLatch started, CountDownLatch interrupted) {
    return tool(
        "blocking",
        arguments -> {
          started.countDown();
          try {
            new CountDownLatch(1).await();
            return ToolResult.success("不应自然完成");
          } catch (InterruptedException exception) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
            return ToolResult.error("不应暴露");
          }
        });
  }

  private static Tool limitedTool(
      CountDownLatch firstStarted, CountDownLatch releaseFirst, AtomicInteger secondExecutions) {
    return tool(
        "limited",
        arguments -> {
          if ("second".equals(arguments.get("order"))) {
            secondExecutions.incrementAndGet();
            return ToolResult.success("second");
          }
          firstStarted.countDown();
          boolean released = false;
          while (!released) {
            try {
              released = releaseFirst.await(1, SECONDS);
            } catch (InterruptedException ignored) {
              // 故意模拟违反中断约定的工具，验证实际退出前不会提前归还许可。
            }
          }
          return ToolResult.success("first");
        });
  }

  private static Tool countingTool(AtomicInteger executions) {
    return tool(
        "blocking",
        arguments -> {
          executions.incrementAndGet();
          return ToolResult.success("executed");
        });
  }

  private static Tool tool(
      String name, java.util.function.Function<Map<String, Object>, ToolResult> action) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(
            name, "测试工具", Map.of("type", "object", "properties", Map.of()), ToolRisk.READ_ONLY);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        return action.apply(arguments);
      }
    };
  }

  private static ChatModelResponse toolResponse(String callId) {
    return new ChatModelResponse("", List.of(new ToolCall(callId, "blocking", Map.of())));
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

  private static final class FirstTaskPausedStarter implements ToolRegistry.ToolTaskStarter {
    private final CountDownLatch firstSubmitted = new CountDownLatch(1);
    private final AtomicReference<Runnable> firstTask = new AtomicReference<>();
    private final AtomicInteger starts = new AtomicInteger();

    @Override
    public void start(String toolName, Runnable task) {
      if (starts.incrementAndGet() == 1) {
        firstTask.set(task);
        firstSubmitted.countDown();
        return;
      }
      Thread.ofVirtual().name("test-tool-" + toolName).start(task);
    }

    private void runFirstTask() {
      firstTask.get().run();
    }
  }
}
