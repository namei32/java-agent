package io.namei.agent.bootstrap.telegram;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatProgressListener;
import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.TurnCancellation;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.error.TurnCancelledException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("failure")
class TelegramChannelFailureTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("retryableFailures")
  void retriesRetryablePollFailuresAtTheSameOffsetAndResetsHealthAfterSuccess(
      String name, TelegramApiException failure) {
    var api = new ScriptedApi();
    var sleeper = new RecordingSleeper();
    api.fail(failure);
    api.succeed(List.of());
    var adapter =
        adapter(api, new NeverCalledChat(), sleeper, ChannelThreadStarter.virtualThreads());

    try {
      adapter.start();
      await(() -> api.requestedOffsets.size() >= 3, "失败、恢复和下一次 Poll");

      assertThat(api.requestedOffsets).as(name).startsWith(0L, 0L, 0L);
      assertThat(sleeper.durations).containsExactly(PROPERTIES.retryBackoff());
      assertThat(adapter.snapshot().state()).isEqualTo(ChannelState.RUNNING);
      assertThat(adapter.snapshot().code()).isEmpty();
      assertThat(adapter.snapshot().consecutiveFailures()).isZero();
    } finally {
      adapter.close();
    }
  }

  private static Stream<Arguments> retryableFailures() {
    return Stream.of(
        Arguments.of("timeout", new TelegramApiException(TelegramApiException.Reason.TIMEOUT)),
        Arguments.of(
            "unavailable", new TelegramApiException(TelegramApiException.Reason.UNAVAILABLE)),
        Arguments.of(
            "rate limited",
            new TelegramApiException(
                TelegramApiException.Reason.RATE_LIMITED, Duration.ofMillis(20))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("permanentFailures")
  void permanentPollFailureFailsImmediatelyWithoutRetry(
      String name, TelegramApiException failure, String expectedCode) {
    var api = new ScriptedApi();
    var sleeper = new RecordingSleeper();
    api.fail(failure);
    var adapter =
        adapter(api, new NeverCalledChat(), sleeper, ChannelThreadStarter.virtualThreads());

    try {
      adapter.start();
      await(() -> adapter.snapshot().state() == ChannelState.FAILED, "永久失败");

      assertThat(api.requestedOffsets).as(name).containsExactly(0L);
      assertThat(sleeper.durations).isEmpty();
      assertThat(adapter.snapshot().code()).isEqualTo(expectedCode);
      assertThat(adapter.snapshot().consecutiveFailures()).isEqualTo(1);
    } finally {
      adapter.close();
    }
  }

  private static Stream<Arguments> permanentFailures() {
    return Stream.of(
        Arguments.of(
            "unauthorized",
            new TelegramApiException(TelegramApiException.Reason.UNAUTHORIZED),
            "POLL_UNAUTHORIZED"),
        Arguments.of(
            "invalid response",
            new TelegramApiException(TelegramApiException.Reason.INVALID_RESPONSE),
            "POLL_INVALID_RESPONSE"));
  }

  @Test
  void exhaustingThePollRetryBudgetDisconnectsActiveTurnsAndReleasesAllCapacity()
      throws InterruptedException {
    var api = new ScriptedApi();
    var sleeper = new RecordingSleeper();
    var chat = new CancellableChat();
    var releaseFailures = new CountDownLatch(1);
    api.succeed(List.of(update(70, "问题")));
    api.failWhen(releaseFailures, TelegramApiException.Reason.TIMEOUT);
    api.fail(TelegramApiException.Reason.UNAVAILABLE);
    api.fail(TelegramApiException.Reason.TIMEOUT);
    var threads = new RecordingThreadStarter();
    var adapter = adapter(api, chat, sleeper, threads);

    try {
      adapter.start();
      assertThat(chat.started.await(2, SECONDS)).isTrue();
      releaseFailures.countDown();
      await(() -> adapter.snapshot().state() == ChannelState.FAILED, "重试耗尽");
      assertThat(chat.cancelled.await(2, SECONDS)).isTrue();
      await(() -> adapter.snapshot().activeTurns() == 0, "断开 Turn 清理");

      assertThat(api.requestedOffsets).containsExactly(0L, 71L, 71L, 71L);
      assertThat(sleeper.durations)
          .containsExactly(PROPERTIES.retryBackoff(), PROPERTIES.retryBackoff());
      assertThat(chat.cancellationReason).hasValue(TurnCancellationCode.CHANNEL_DISCONNECTED);
      assertThat(adapter.snapshot().code()).isEqualTo("POLL_RETRY_EXHAUSTED");
      assertThat(adapter.snapshot().consecutiveFailures()).isEqualTo(3);
      assertThat(adapter.nextOffset()).isEqualTo(71);
      assertThat(adapter.availableTurnPermits()).isEqualTo(1);
      await(threads::allStopped, "所有 Poll/Turn Worker 退出");
    } finally {
      adapter.close();
    }
  }

  @Test
  void closeInterruptsPollCancelsActiveTurnWithShutdownAndJoinsEveryWorker()
      throws InterruptedException {
    var api = new ScriptedApi();
    var chat = new CancellableChat();
    var threads = new RecordingThreadStarter();
    api.succeed(List.of(update(80, "问题")));
    var adapter = adapter(api, chat, new RecordingSleeper(), threads);

    adapter.start();
    assertThat(chat.started.await(2, SECONDS)).isTrue();
    await(() -> api.requestedOffsets.size() >= 2, "阻塞中的第二次 Poll");

    adapter.close();

    assertThat(api.pollInterruptions).hasValue(1);
    assertThat(chat.cancellationReason).hasValue(TurnCancellationCode.SHUTDOWN);
    assertThat(adapter.snapshot().state()).isEqualTo(ChannelState.STOPPED);
    assertThat(adapter.snapshot().activeTurns()).isZero();
    assertThat(adapter.availableTurnPermits()).isEqualTo(1);
    assertThat(threads.allStopped()).isTrue();
  }

  @Test
  void requestedCancellationWinsWhenCloseRacesTheProducerTerminal() throws Exception {
    var api = new ScriptedApi();
    var chat = new CancellationRaceChat();
    var threads = new RecordingThreadStarter();
    api.succeed(List.of(update(90, "问题")));
    var adapter = adapter(api, chat, new RecordingSleeper(), threads);

    adapter.start();
    assertThat(chat.started.await(2, SECONDS)).isTrue();
    api.succeed(List.of(update(91, "/cancel")));
    assertThat(chat.cancelled.await(2, SECONDS)).isTrue();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var closing = executor.submit(adapter::close);
      await(() -> adapter.snapshot().state() == ChannelState.STOPPING, "并发关闭开始");
      chat.finish.countDown();
      closing.get(2, SECONDS);
    }

    assertThat(chat.cancellationReason).hasValue(TurnCancellationCode.REQUESTED);
    assertThat(adapter.snapshot().state()).isEqualTo(ChannelState.STOPPED);
    assertThat(adapter.snapshot().activeTurns()).isZero();
    assertThat(adapter.availableTurnPermits()).isEqualTo(1);
    assertThat(threads.allStopped()).isTrue();
  }

  @Test
  void closingBeforeThePollTaskRunsDoesNotTouchTheApi() throws InterruptedException {
    var api = new ScriptedApi();
    var threads = new GatedThreadStarter("telegram-poll-worker");
    var adapter = adapter(api, new NeverCalledChat(), new RecordingSleeper(), threads);

    adapter.start();
    assertThat(threads.gated.await(2, SECONDS)).isTrue();

    adapter.close();

    assertThat(api.requestedOffsets).isEmpty();
    assertThat(adapter.snapshot().state()).isEqualTo(ChannelState.STOPPED);
    assertThat(adapter.availableTurnPermits()).isEqualTo(1);
    assertThat(threads.allStopped()).isTrue();
  }

  @Test
  void closingBeforeTheTurnTaskRunsDoesNotAdvanceOffsetOrInvokeBusinessWork()
      throws InterruptedException {
    var api = new ScriptedApi();
    var chat = new NeverCalledChat();
    var threads = new GatedThreadStarter("telegram-turn-worker");
    api.succeed(List.of(update(100, "问题")));
    var adapter = adapter(api, chat, new RecordingSleeper(), threads);

    adapter.start();
    assertThat(threads.gated.await(2, SECONDS)).isTrue();
    await(
        () -> {
          Thread poll = threads.thread("telegram-poll-worker");
          return poll != null
              && (poll.getState() == Thread.State.WAITING
                  || poll.getState() == Thread.State.TIMED_WAITING);
        },
        "Poll 等待 Turn 启动握手");

    adapter.close();

    assertThat(chat.calls).hasValue(0);
    assertThat(adapter.nextOffset()).isZero();
    assertThat(adapter.snapshot().state()).isEqualTo(ChannelState.STOPPED);
    assertThat(adapter.snapshot().activeTurns()).isZero();
    assertThat(adapter.availableTurnPermits()).isEqualTo(1);
    assertThat(threads.allStopped()).isTrue();
  }

  @Test
  void pollWorkerStartupFailureIsFailClosedAndRedacted() {
    var api = new ScriptedApi();
    ChannelThreadStarter starter =
        (name, task) -> {
          throw new IllegalStateException("poll-start-secret");
        };
    var adapter = adapter(api, new NeverCalledChat(), new RecordingSleeper(), starter);

    assertThatThrownBy(adapter::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Telegram Channel 启动失败")
        .hasMessageNotContaining("poll-start-secret")
        .hasNoCause();
    assertThat(adapter.snapshot().state()).isEqualTo(ChannelState.FAILED);
    assertThat(adapter.snapshot().code()).isEqualTo("POLL_WORKER_START_FAILED");
    assertThat(adapter.snapshot().activeTurns()).isZero();
    assertThat(adapter.availableTurnPermits()).isEqualTo(1);
    assertThat(api.requestedOffsets).isEmpty();

    adapter.close();
    assertThat(adapter.snapshot().state()).isEqualTo(ChannelState.STOPPED);
  }

  private static TelegramChannelAdapter adapter(
      ScriptedApi api, ChatUseCase chat, ChannelSleeper sleeper, ChannelThreadStarter starter) {
    var ids = new AtomicLong();
    return new TelegramChannelAdapter(
        api,
        new TelegramUpdateMapper(Set.of(10001L), () -> "turn-failure-" + ids.incrementAndGet()),
        new MessageTurnService(chat),
        PROPERTIES,
        starter,
        sleeper);
  }

  private static final TelegramProperties PROPERTIES =
      new TelegramProperties(
          true,
          List.of("10001"),
          1,
          4,
          Duration.ofSeconds(1),
          Duration.ofMillis(20),
          Duration.ofSeconds(1),
          Duration.ofSeconds(1),
          Duration.ofSeconds(2),
          Duration.ofSeconds(1),
          Duration.ofSeconds(2),
          Duration.ofMillis(10),
          Duration.ofSeconds(1));

  private static TelegramUpdate update(long updateId, String text) {
    return new TelegramUpdate(
        updateId,
        new TelegramMessage(
            updateId + 1,
            Instant.parse("2026-07-16T00:00:00Z"),
            10001,
            "private",
            10001,
            false,
            text));
  }

  private static void await(BooleanSupplier condition, String description) {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("等待超时: " + description);
      }
      Thread.onSpinWait();
    }
  }

  @FunctionalInterface
  private interface PollAction {
    List<TelegramUpdate> execute();
  }

  private static final class ScriptedApi implements TelegramBotApi {
    private final BlockingQueue<PollAction> actions = new LinkedBlockingQueue<>();
    private final List<Long> requestedOffsets = new CopyOnWriteArrayList<>();
    private final List<String> sends = new CopyOnWriteArrayList<>();
    private final AtomicInteger pollInterruptions = new AtomicInteger();

    private void succeed(List<TelegramUpdate> updates) {
      List<TelegramUpdate> copy = List.copyOf(updates);
      actions.add(() -> copy);
    }

    private void fail(TelegramApiException.Reason reason) {
      fail(new TelegramApiException(reason));
    }

    private void fail(TelegramApiException failure) {
      actions.add(
          () -> {
            throw failure;
          });
    }

    private void failWhen(CountDownLatch release, TelegramApiException.Reason reason) {
      actions.add(
          () -> {
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              pollInterruptions.incrementAndGet();
              Thread.currentThread().interrupt();
              throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
            }
            throw new TelegramApiException(reason);
          });
    }

    @Override
    public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
      requestedOffsets.add(offset);
      try {
        return actions.take().execute();
      } catch (InterruptedException interrupted) {
        pollInterruptions.incrementAndGet();
        Thread.currentThread().interrupt();
        throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
      }
    }

    @Override
    public void sendMessage(long chatId, String text) {
      sends.add(text);
    }
  }

  private static final class RecordingSleeper implements ChannelSleeper {
    private final List<Duration> durations = new CopyOnWriteArrayList<>();

    @Override
    public void sleep(Duration duration) {
      durations.add(duration);
    }
  }

  private static class RecordingThreadStarter implements ChannelThreadStarter {
    protected final List<Thread> threads = new CopyOnWriteArrayList<>();

    @Override
    public Thread start(String name, Runnable task) {
      Thread thread = Thread.ofVirtual().name(name).start(task);
      threads.add(thread);
      return thread;
    }

    final boolean allStopped() {
      return threads.stream().noneMatch(Thread::isAlive);
    }

    final Thread thread(String name) {
      return threads.stream()
          .filter(thread -> thread.getName().equals(name))
          .findFirst()
          .orElse(null);
    }
  }

  private static final class GatedThreadStarter extends RecordingThreadStarter {
    private final String gatedName;
    private final CountDownLatch gated = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private GatedThreadStarter(String gatedName) {
      this.gatedName = gatedName;
    }

    @Override
    public Thread start(String name, Runnable task) {
      if (!name.equals(gatedName)) {
        return super.start(name, task);
      }
      return super.start(
          name,
          () -> {
            gated.countDown();
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return;
            }
            task.run();
          });
    }
  }

  private static final class NeverCalledChat implements ChatUseCase {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResult chat(ChatCommand command) {
      calls.incrementAndGet();
      throw new AssertionError("不应调用业务 Chat");
    }
  }

  private static class CancellableChat implements ChatUseCase {
    protected final CountDownLatch started = new CountDownLatch(1);
    protected final CountDownLatch cancelled = new CountDownLatch(1);
    protected final AtomicReference<TurnCancellationCode> cancellationReason =
        new AtomicReference<>();

    @Override
    public ChatResult chat(ChatCommand command) {
      throw new AssertionError("必须传播取消 Token");
    }

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      var release = new CountDownLatch(1);
      try (var registration =
          cancellation.onCancellation(
              () -> {
                cancellationReason.set(cancellation.reason());
                cancelled.countDown();
                release.countDown();
              })) {
        started.countDown();
        if (!release.await(2, SECONDS)) {
          throw new TurnCancelledException("测试未收到取消");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TurnCancelledException("测试 Turn 被中断");
      }
      throw new TurnCancelledException("测试 Turn 已取消");
    }
  }

  private static final class CancellationRaceChat extends CancellableChat {
    private final CountDownLatch finish = new CountDownLatch(1);

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      try (var registration =
          cancellation.onCancellation(
              () -> {
                cancellationReason.set(cancellation.reason());
                cancelled.countDown();
              })) {
        started.countDown();
        if (!cancelled.await(2, SECONDS) || !finish.await(2, SECONDS)) {
          throw new AssertionError("取消竞态未释放");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TurnCancelledException("测试 Turn 被中断");
      }
      throw new TurnCancelledException("测试 Turn 已取消");
    }
  }
}
