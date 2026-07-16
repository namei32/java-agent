package io.namei.agent.bootstrap.cli;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatProgressListener;
import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.TurnCancellation;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.channel.TurnFailureCode;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LocalCliRunnerTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
  private static final CliProperties PROPERTIES =
      new CliProperties(
          "cli:trusted-session",
          "trusted-conversation",
          8,
          Duration.ofSeconds(1),
          Duration.ofMillis(100));

  @Test
  void rendersEveryVersionedCliFixtureCase() throws Exception {
    JsonNode cases = fixture().path("cliCases");
    assertThat(cases).hasSize(6);
    int rendered = 0;

    for (JsonNode testCase : cases) {
      JsonNode input = testCase.path("input");
      if (input.has("lines")) {
        continue;
      }
      var output = new RecordingOutput();
      var renderer = new CliMessageRenderer(output);
      var route = new MessageRoute("cli", "fixture");
      String turnId = "turn-" + rendered;
      renderer.accept(OutboundMessage.started(turnId, "fixture-session", route));
      long sequence = 1;
      for (JsonNode delta : input.path("deltas")) {
        renderer.accept(
            OutboundMessage.delta(turnId, "fixture-session", route, sequence++, delta.asString()));
      }
      if (input.has("finalContent")) {
        renderer.accept(
            OutboundMessage.completed(
                turnId, "fixture-session", route, sequence, input.path("finalContent").asString()));
      } else if (input.has("failureCode")) {
        renderer.accept(
            OutboundMessage.failed(
                turnId,
                "fixture-session",
                route,
                sequence,
                TurnFailureCode.valueOf(input.path("failureCode").asString())));
      } else {
        renderer.accept(
            OutboundMessage.cancelled(
                turnId,
                "fixture-session",
                route,
                sequence,
                TurnCancellationCode.valueOf(input.path("cancellationCode").asString())));
      }

      assertThat(output.stdout.toString())
          .as(testCase.path("id").asString())
          .isEqualTo(testCase.path("expected").path("stdout").asString(""));
      assertThat(output.stderr.toString())
          .as(testCase.path("id").asString())
          .isEqualTo(testCase.path("expected").path("stderr").asString(""));
      assertThat(renderer.isTerminal()).isTrue();
      rendered++;
    }

    assertThat(rendered).isEqualTo(5);
  }

  @Test
  void ignoresFixtureBlankLinesAndUsesOnlyTrustedSessionAndRoute() throws Exception {
    JsonNode testCase = fixtureCase("blank-input-is-ignored");
    var lines = new ArrayList<String>();
    testCase.path("input").path("lines").forEach(line -> lines.add(line.asString()));
    var commands = new CopyOnWriteArrayList<ChatCommand>();
    var ids = new DeterministicIds();
    var output = new RecordingOutput();
    var runner =
        runner(
            new QueueInput(lines),
            output,
            ids,
            new RecordingThreadStarter(),
            (command, cancellation, progress) -> {
              commands.add(command);
              progress.onContentDelta("回");
              progress.onContentDelta("答");
              return result(command, "回答");
            });

    runner.run();

    assertThat(commands).hasSize(testCase.path("expected").path("turns").asInt());
    assertThat(commands.getFirst().sessionId()).isEqualTo("cli:trusted-session");
    assertThat(commands.getFirst().message()).isEqualTo("问题");
    assertThat(ids.messageCalls.get()).isEqualTo(1);
    assertThat(ids.turnCalls.get()).isEqualTo(1);
    assertThat(output.stdout.toString()).isEqualTo("回答\n");
    assertThat(output.stderr).isEmpty();
  }

  @Test
  @Tag("failure")
  void mapsModelFailureToStableCodeWithoutLeakingExceptionText() {
    var output = new RecordingOutput();
    var runner =
        runner(
            new QueueInput(List.of("问题")),
            output,
            new DeterministicIds(),
            new RecordingThreadStarter(),
            (command, cancellation, progress) -> {
              throw new ModelTimeoutException(
                  "provider-secret-timeout", new IllegalStateException("provider-secret"));
            });

    runner.run();

    assertThat(output.stdout).isEmpty();
    assertThat(output.stderr.toString()).isEqualTo("MODEL_TIMEOUT\n");
    assertThat(output.stderr.toString()).doesNotContain("provider-secret-timeout");
  }

  @Test
  @Tag("failure")
  void outputFailureDisconnectsAndCancelsTheActiveTurnWithoutLeakingAThread() throws Exception {
    var cancellationReason = new AtomicReference<TurnCancellationCode>();
    var waiting = new CountDownLatch(1);
    var starter = new RecordingThreadStarter();
    var output = new FailingOutput();
    var runner =
        runner(
            new QueueInput(List.of("问题")),
            output,
            new DeterministicIds(),
            starter,
            (command, cancellation, progress) -> {
              try (var ignored =
                  cancellation.onCancellation(
                      () -> {
                        cancellationReason.set(cancellation.reason());
                        waiting.countDown();
                      })) {
                progress.onContentDelta("部分");
                if (!waiting.await(2, SECONDS)) {
                  throw new AssertionError("未收到渠道断开取消");
                }
                throw new TurnCancelledException("late-provider-secret");
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new TurnCancelledException("测试等待被中断");
              }
            });

    assertThatThrownBy(runner::run)
        .isInstanceOf(CliOutputException.class)
        .hasMessage("CLI stdout 写入失败")
        .hasMessageNotContaining("output-secret");

    assertThat(cancellationReason.get()).isEqualTo(TurnCancellationCode.CHANNEL_DISCONNECTED);
    assertThat(starter.started)
        .singleElement()
        .satisfies(thread -> assertThat(thread.isAlive()).isFalse());
  }

  @Test
  @Tag("failure")
  void startupFailureDoesNotInvokeChatAndUsesSafeError() {
    var calls = new AtomicInteger();
    CliThreadStarter failingStarter =
        (name, task) -> {
          throw new IllegalStateException("thread-start-secret");
        };
    var runner =
        runner(
            new QueueInput(List.of("问题")),
            new RecordingOutput(),
            new DeterministicIds(),
            failingStarter,
            (command, cancellation, progress) -> {
              calls.incrementAndGet();
              return result(command, "不应调用");
            });

    assertThatThrownBy(runner::run)
        .isInstanceOf(CliRunnerException.class)
        .hasMessage("无法启动 CLI Turn")
        .hasMessageNotContaining("thread-start-secret");
    assertThat(calls).hasValue(0);
  }

  @Test
  @Tag("failure")
  void shutdownCancelsActiveTurnAndJoinsProducer() throws Exception {
    var cancellationReason = new AtomicReference<TurnCancellationCode>();
    var ready = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var starter = new RecordingThreadStarter();
    var runner =
        runner(
            new QueueInput(List.of("问题")),
            new RecordingOutput(),
            new DeterministicIds(),
            starter,
            (command, cancellation, progress) -> {
              try (var ignored =
                  cancellation.onCancellation(
                      () -> {
                        cancellationReason.set(cancellation.reason());
                        cancelled.countDown();
                      })) {
                ready.countDown();
                if (!cancelled.await(2, SECONDS)) {
                  throw new AssertionError("未收到 Shutdown 取消");
                }
                throw new TurnCancelledException("shutdown");
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new TurnCancelledException("测试等待被中断");
              }
            });

    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<?> running = executor.submit(runner::run);
      assertThat(ready.await(2, SECONDS)).isTrue();

      runner.shutdown();

      getWithoutFailure(running);
    }

    assertThat(cancellationReason.get()).isEqualTo(TurnCancellationCode.SHUTDOWN);
    assertThat(starter.started)
        .singleElement()
        .satisfies(thread -> assertThat(thread.isAlive()).isFalse());
    assertThat(runner.isShutdown()).isTrue();
    runner.shutdown();
  }

  @Test
  void validatesCliConfigurationBounds() {
    assertThatThrownBy(
            () ->
                new CliProperties(
                    " ", "conversation", 8, Duration.ofSeconds(1), Duration.ofMillis(100)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CliProperties(
                    "session", "conversation", 0, Duration.ofSeconds(1), Duration.ofMillis(100)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CliProperties(
                    "session", "conversation", 8, Duration.ZERO, Duration.ofMillis(100)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CliProperties(
                    "session", "conversation", 8, Duration.ofSeconds(1), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static LocalCliRunner runner(
      CliInput input,
      CliOutput output,
      CliIdGenerator ids,
      CliThreadStarter starter,
      StreamingBehavior behavior) {
    var useCase =
        new ChatUseCase() {
          @Override
          public ChatResult chat(ChatCommand command) {
            return chat(command, TurnCancellation.none(), ChatProgressListener.noop());
          }

          @Override
          public ChatResult chat(
              ChatCommand command,
              TurnCancellation cancellation,
              ChatProgressListener progressListener) {
            return behavior.chat(command, cancellation, progressListener);
          }
        };
    return new LocalCliRunner(
        new MessageTurnService(useCase), PROPERTIES, CLOCK, ids, input, output, starter);
  }

  private static ChatResult result(ChatCommand command, String content) {
    return new ChatResult(command.sessionId(), new ChatMessage(MessageRole.ASSISTANT, content));
  }

  private static JsonNode fixture() throws Exception {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return JSON.readTree(
        Path.of(configured)
            .toAbsolutePath()
            .normalize()
            .resolve("message-bus/provider-streaming-cli.json"));
  }

  private static JsonNode fixtureCase(String id) throws Exception {
    for (JsonNode testCase : fixture().path("cliCases")) {
      if (id.equals(testCase.path("id").asString())) {
        return testCase;
      }
    }
    throw new AssertionError("缺少 CLI Fixture: " + id);
  }

  private static void getWithoutFailure(Future<?> future) throws Exception {
    try {
      future.get(2, SECONDS);
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw new AssertionError(exception.getCause());
    }
  }

  @FunctionalInterface
  private interface StreamingBehavior {
    ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener);
  }

  private static final class QueueInput implements CliInput {
    private final List<String> lines;
    private int index;

    private QueueInput(List<String> lines) {
      this.lines = List.copyOf(lines);
    }

    @Override
    public String readLine() {
      return index == lines.size() ? null : lines.get(index++);
    }
  }

  private static class RecordingOutput implements CliOutput {
    protected final StringBuilder stdout = new StringBuilder();
    protected final StringBuilder stderr = new StringBuilder();

    @Override
    public void writeStdout(String text) {
      stdout.append(text);
    }

    @Override
    public void writeStderr(String text) {
      stderr.append(text);
    }
  }

  private static final class FailingOutput extends RecordingOutput {
    @Override
    public void writeStdout(String text) {
      throw new CliOutputException("CLI stdout 写入失败", new IllegalStateException("output-secret"));
    }
  }

  private static final class DeterministicIds implements CliIdGenerator {
    private final AtomicInteger messageCalls = new AtomicInteger();
    private final AtomicInteger turnCalls = new AtomicInteger();

    @Override
    public String newMessageId() {
      return "message-" + messageCalls.incrementAndGet();
    }

    @Override
    public String newTurnId() {
      return "turn-" + turnCalls.incrementAndGet();
    }
  }

  private static final class RecordingThreadStarter implements CliThreadStarter {
    private final List<Thread> started = new CopyOnWriteArrayList<>();

    @Override
    public Thread start(String name, Runnable task) {
      Thread thread = Thread.ofVirtual().name(name).start(task);
      started.add(thread);
      return thread;
    }
  }
}
