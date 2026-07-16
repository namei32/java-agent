package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatProgressListener;
import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.TurnCancellation;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TelegramChannelAdapterTest {
  @Test
  void usesOnePollWorkerAdvancesOffsetAndStartsEachUpdateAtMostOnce() {
    var api = new ScriptedApi();
    var chat = new SuccessfulChat();
    api.enqueue(
        List.of(
            update(10, 10001, 1, "问题"),
            update(10, 10001, 1, "重复"),
            update(11, 10001, 2, "群聊", "group", false),
            update(12, 10003, 3, "未授权")));
    var adapter = adapter(api, chat, properties(2), ChannelThreadStarter.virtualThreads());

    try {
      adapter.start();
      await(() -> api.requestedOffsets.size() >= 2, "第二次 Poll");
      await(() -> api.sends.size() == 1, "权威终态发送");
      await(() -> adapter.snapshot().activeTurns() == 0, "Turn 释放");

      assertThat(api.requestedOffsets).startsWith(0L, 13L);
      assertThat(api.maxConcurrentPolls).hasValue(1);
      assertThat(chat.calls).hasValue(1);
      assertThat(api.sends).containsExactly(new Send(10001, "answer-telegram:10001"));
      assertThat(adapter.nextOffset()).isEqualTo(13);
      assertThat(adapter.availableTurnPermits()).isEqualTo(2);
      assertThat(adapter.snapshot().activeTurns()).isZero();
      assertThatThrownBy(adapter::start).isInstanceOf(IllegalStateException.class);
    } finally {
      adapter.close();
    }
  }

  @Test
  void rejectsSameSessionAndGlobalConcurrencyWithoutStartingExtraTurns()
      throws InterruptedException {
    var api = new ScriptedApi();
    var chat = new BlockingChat("telegram:10001");
    api.enqueue(
        List.of(
            update(20, 10001, 1, "第一问"),
            update(21, 10001, 2, "同会话第二问"),
            update(22, 10002, 1, "超过全局容量")));
    var adapter = adapter(api, chat, properties(1), ChannelThreadStarter.virtualThreads());

    try {
      adapter.start();
      assertThat(chat.awaitStarted("telegram:10001")).isTrue();
      await(
          () ->
              api.sends.stream()
                      .filter(send -> send.text().equals(TelegramChannelAdapter.SESSION_BUSY_TEXT))
                      .count()
                  == 2,
          "两个 Busy 回复");

      assertThat(chat.calls).hasValue(1);
      assertThat(adapter.snapshot().activeTurns()).isEqualTo(1);
      assertThat(adapter.availableTurnPermits()).isZero();
      await(() -> api.requestedOffsets.contains(23L), "安全推进 offset");

      chat.release("telegram:10001");
      await(() -> api.sends.stream().anyMatch(send -> send.text().startsWith("answer-")), "完成回复");
      await(() -> adapter.snapshot().activeTurns() == 0, "Turn 释放");
      assertThat(adapter.availableTurnPermits()).isEqualTo(1);
    } finally {
      adapter.close();
    }
  }

  @Test
  void cancelTargetsOnlyTheAuthorizedConversationAndNeverBecomesAPrompt()
      throws InterruptedException {
    var api = new ScriptedApi();
    var chat = new BlockingChat("telegram:10001", "telegram:10002");
    api.enqueue(List.of(update(30, 10001, 1, "第一问"), update(31, 10002, 1, "第二问")));
    var adapter = adapter(api, chat, properties(2), ChannelThreadStarter.virtualThreads());

    try {
      adapter.start();
      assertThat(chat.awaitStarted("telegram:10001")).isTrue();
      assertThat(chat.awaitStarted("telegram:10002")).isTrue();
      api.enqueue(List.of(update(32, 10001, 2, "/cancel")));

      await(() -> api.sends.contains(new Send(10001, "请求已取消（REQUESTED）")), "目标会话取消终态");
      assertThat(chat.commands)
          .extracting(ChatCommand::message)
          .containsExactlyInAnyOrder("第一问", "第二问")
          .doesNotContain("/cancel");
      assertThat(chat.cancelledSessions).containsExactly("telegram:10001");
      assertThat(chat.isReleased("telegram:10002")).isFalse();
      assertThat(api.sends).noneMatch(send -> send.chatId() == 10002);

      chat.release("telegram:10002");
      await(() -> api.sends.contains(new Send(10002, "answer-telegram:10002")), "另一会话正常完成");
      await(() -> adapter.snapshot().activeTurns() == 0, "全部 Turn 释放");
    } finally {
      adapter.close();
    }
  }

  @Test
  void authorizedControlWithoutAnActiveTurnReturnsOneFixedMessage() {
    var api = new ScriptedApi();
    var chat = new SuccessfulChat();
    api.enqueue(List.of(update(40, 10001, 1, "/stop")));
    var adapter = adapter(api, chat, properties(1), ChannelThreadStarter.virtualThreads());

    try {
      adapter.start();
      await(
          () -> api.sends.contains(new Send(10001, TelegramChannelAdapter.NO_ACTIVE_TURN_TEXT)),
          "无活动 Turn 回复");
      assertThat(chat.calls).hasValue(0);
      await(() -> api.requestedOffsets.contains(41L), "控制命令推进 offset");
    } finally {
      adapter.close();
    }
  }

  @Test
  void unsupportedAndUnauthorizedUpdatesNeverReachBusinessWorkOrDelivery() {
    var api = new ScriptedApi();
    var chat = new SuccessfulChat();
    api.enqueue(
        List.of(
            update(50, 10001, 1, "群聊", "group", false),
            update(51, 10001, 2, "Bot", "private", true),
            update(52, 10003, 1, "未授权"),
            update(53, 10001, 3, null),
            new TelegramUpdate(54, null)));
    var adapter = adapter(api, chat, properties(2), ChannelThreadStarter.virtualThreads());

    try {
      adapter.start();
      await(() -> api.requestedOffsets.contains(55L), "拒绝输入推进 offset");

      assertThat(chat.calls).hasValue(0);
      assertThat(api.sends).isEmpty();
      assertThat(adapter.snapshot().activeTurns()).isZero();
      assertThat(adapter.availableTurnPermits()).isEqualTo(2);
    } finally {
      adapter.close();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("workerStartupFailures")
  @Tag("failure")
  void workerStartupFailureDoesNotAdvanceOffsetOrLeakConversationPermitOrThread(
      String name, String failingWorker) {
    var api = new ScriptedApi();
    var chat = new SuccessfulChat();
    api.enqueue(List.of(update(60, 10001, 1, "不能开始")));
    var threads = new FailingThreadStarter(failingWorker);
    var adapter = adapter(api, chat, properties(1), threads);

    try {
      adapter.start();
      await(() -> adapter.snapshot().state() == ChannelState.FAILED, "Adapter 进入 FAILED");
      await(() -> adapter.snapshot().activeTurns() == 0, "失败 Turn 清理");
      await(() -> threads.started.stream().noneMatch(Thread::isAlive), "Worker 退出");

      assertThat(adapter.nextOffset()).as(name).isZero();
      assertThat(adapter.availableTurnPermits()).isEqualTo(1);
      assertThat(chat.calls).hasValue(0);
      assertThat(api.sends).isEmpty();
      assertThat(adapter.snapshot().code()).isEqualTo("TURN_WORKER_START_FAILED");
    } finally {
      adapter.close();
    }
  }

  private static Stream<Arguments> workerStartupFailures() {
    return Stream.of(
        Arguments.of("outer worker", "telegram-turn-worker"),
        Arguments.of("producer", "telegram-turn-producer"));
  }

  private static TelegramChannelAdapter adapter(
      ScriptedApi api,
      ChatUseCase chat,
      TelegramProperties properties,
      ChannelThreadStarter threads) {
    var ids = new AtomicLong();
    return new TelegramChannelAdapter(
        api,
        new TelegramUpdateMapper(
            Set.of(10001L, 10002L), () -> "turn-test-" + ids.incrementAndGet()),
        new MessageTurnService(chat),
        properties,
        threads,
        duration -> {});
  }

  private static TelegramProperties properties(int maxConcurrentTurns) {
    return new TelegramProperties(
        true,
        List.of("10001", "10002"),
        maxConcurrentTurns,
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
  }

  private static TelegramUpdate update(long updateId, long chatId, long messageId, String text) {
    return update(updateId, chatId, messageId, text, "private", false);
  }

  private static TelegramUpdate update(
      long updateId, long chatId, long messageId, String text, String chatType, boolean senderBot) {
    return new TelegramUpdate(
        updateId,
        new TelegramMessage(
            messageId,
            Instant.parse("2026-07-16T00:00:00Z"),
            chatId,
            chatType,
            chatId,
            senderBot,
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

  private record Send(long chatId, String text) {}

  private static final class ScriptedApi implements TelegramBotApi {
    private final BlockingQueue<List<TelegramUpdate>> batches = new LinkedBlockingQueue<>();
    private final List<Long> requestedOffsets = new CopyOnWriteArrayList<>();
    private final List<Send> sends = new CopyOnWriteArrayList<>();
    private final AtomicInteger activePolls = new AtomicInteger();
    private final AtomicInteger maxConcurrentPolls = new AtomicInteger();

    private void enqueue(List<TelegramUpdate> updates) {
      batches.add(List.copyOf(updates));
    }

    @Override
    public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
      requestedOffsets.add(offset);
      int active = activePolls.incrementAndGet();
      maxConcurrentPolls.accumulateAndGet(active, Math::max);
      try {
        return batches.take();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
      } finally {
        activePolls.decrementAndGet();
      }
    }

    @Override
    public void sendMessage(long chatId, String text) {
      sends.add(new Send(chatId, text));
    }
  }

  private static final class SuccessfulChat implements ChatUseCase {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResult chat(ChatCommand command) {
      return answer(command);
    }

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      calls.incrementAndGet();
      progressListener.onContentDelta("preview-secret");
      return answer(command);
    }

    private static ChatResult answer(ChatCommand command) {
      return new ChatResult(
          command.sessionId(),
          new ChatMessage(MessageRole.ASSISTANT, "answer-" + command.sessionId()));
    }
  }

  private static final class BlockingChat implements ChatUseCase {
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<ChatCommand> commands = new CopyOnWriteArrayList<>();
    private final List<String> cancelledSessions = new CopyOnWriteArrayList<>();

    private BlockingChat(String... sessions) {
      for (String session : sessions) {
        gates.put(session, new Gate());
      }
    }

    @Override
    public ChatResult chat(ChatCommand command) {
      return chat(command, TurnCancellation.none(), ChatProgressListener.noop());
    }

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      calls.incrementAndGet();
      commands.add(command);
      Gate gate = gates.get(command.sessionId());
      if (gate == null) {
        throw new IllegalStateException("缺少测试 Gate");
      }
      gate.started.countDown();
      try (var registration = cancellation.onCancellation(gate.release::countDown)) {
        gate.release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TurnCancelledException("测试 Turn 被中断");
      }
      if (cancellation.isCancellationRequested()) {
        cancelledSessions.add(command.sessionId());
        throw new TurnCancelledException("测试 Turn 已取消");
      }
      return new ChatResult(
          command.sessionId(),
          new ChatMessage(MessageRole.ASSISTANT, "answer-" + command.sessionId()));
    }

    private boolean awaitStarted(String session) throws InterruptedException {
      return gates.get(session).started.await(2, TimeUnit.SECONDS);
    }

    private void release(String session) {
      gates.get(session).release.countDown();
    }

    private boolean isReleased(String session) {
      return gates.get(session).release.getCount() == 0;
    }

    private static final class Gate {
      private final CountDownLatch started = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
    }
  }

  private static final class FailingThreadStarter implements ChannelThreadStarter {
    private final String failingWorker;
    private final List<Thread> started = new CopyOnWriteArrayList<>();

    private FailingThreadStarter(String failingWorker) {
      this.failingWorker = failingWorker;
    }

    @Override
    public Thread start(String name, Runnable task) {
      if (name.equals(failingWorker)) {
        throw new IllegalStateException("thread-start-secret");
      }
      Thread thread = Thread.ofVirtual().name(name).start(task);
      started.add(thread);
      return thread;
    }
  }
}
