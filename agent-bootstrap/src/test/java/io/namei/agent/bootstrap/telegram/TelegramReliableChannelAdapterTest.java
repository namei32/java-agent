package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.sqlite.ChannelLedgerSchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcChannelLedger;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatProgressListener;
import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.TurnCancellation;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityMode;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityProperties;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityRuntime;
import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.reliability.ChannelFingerprint;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelegramReliableChannelAdapterTest {
  @TempDir Path temporaryDirectory;

  @Test
  void derivesTheInstanceOnlyFromBotIdSoRotationIsStableAndBotsAreIsolated() {
    ChannelInstanceId first =
        TelegramChannelInstance.from(
                new TelegramBotToken("123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234"))
            .id();
    ChannelInstanceId rotated =
        TelegramChannelInstance.from(
                new TelegramBotToken("123456789:abcdefghijklmnopqrstuvwxyz-5678"))
            .id();
    ChannelInstanceId other =
        TelegramChannelInstance.from(
                new TelegramBotToken("987654321:ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234"))
            .id();

    assertThat(first).isEqualTo(rotated).isNotEqualTo(other);
    assertThat(first.channel()).isEqualTo("telegram");
    assertThat(first.toString())
        .doesNotContain("123456789", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz");
  }

  @Test
  void initializesRecoversAndReadsTheDurableOffsetBeforeItsFirstPoll() {
    Path workspace = temporaryDirectory.resolve("workspace");
    var firstApi = new ScriptedApi();
    var attemptWasDurableBeforeNetwork = new AtomicBoolean();
    firstApi.beforeSend =
        () ->
            attemptWasDurableBeforeNetwork.set(
                attemptStarted(workspace.resolve("channels/channel-ledger.db")));
    firstApi.enqueue(List.of(update(10, 1, "question")));
    TelegramReliableChannelAdapter first = adapter(workspace, firstApi);

    first.start();
    await(() -> firstApi.sends.size() == 1, "首次可靠终态投递");
    assertThat(attemptWasDurableBeforeNetwork).isTrue();
    await(() -> first.nextOffset() == 11, "持久 Offset 推进");
    first.close();

    Path database = workspace.resolve("channels/channel-ledger.db");
    assertThat(database).isRegularFile();
    var schema = new ChannelLedgerSchemaInitializer(database, 5_000);
    schema.initialize();
    ChannelInstanceId instance =
        TelegramChannelInstance.from(
                new TelegramBotToken("123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234"))
            .id();
    assertThat(new JdbcChannelLedger(schema).snapshot(instance).nextSequence()).isEqualTo(11);

    var restartedApi = new ScriptedApi();
    TelegramReliableChannelAdapter restarted = adapter(workspace, restartedApi);
    try {
      restarted.start();
      await(() -> !restartedApi.requestedOffsets.isEmpty(), "重启后的首次 Poll");
      assertThat(restartedApi.requestedOffsets.getFirst()).isEqualTo(11);
      assertThat(restarted.snapshot().state()).isEqualTo(ChannelState.RUNNING);
    } finally {
      restarted.close();
    }

    assertThat(reliableWorkers()).isEmpty();
  }

  @Test
  void persistsAcceptedBusyIgnoredAndControlBeforeTheirVisibleEffects() throws Exception {
    Path workspace = temporaryDirectory.resolve("decisions-workspace");
    var api = new ScriptedApi();
    var chat = new CancellableChat();
    api.enqueue(
        List.of(
            update(20, 10001, 1, "first"),
            update(21, 10002, 1, "busy"),
            update(22, 99999, 1, "ignored"),
            update(23, 10001, 2, "/cancel")));
    TelegramReliableChannelAdapter adapter = adapter(workspace, api, chat, Set.of(10001L, 10002L));

    try {
      adapter.start();
      assertThat(chat.started.await(2, TimeUnit.SECONDS)).isTrue();
      await(() -> api.sends.size() == 2, "Busy 与取消终态投递");
      await(() -> adapter.nextOffset() == 24, "四类决策持久推进");

      assertThat(chat.calls).hasValue(1);
      assertThat(api.sends)
          .extracting(Send::text)
          .containsExactlyInAnyOrder(TelegramChannelAdapter.SESSION_BUSY_TEXT, "请求已取消（REQUESTED）");
    } finally {
      adapter.close();
    }

    assertThat(decisions(workspace.resolve("channels/channel-ledger.db")))
        .containsExactly("TURN_RESERVED", "FEEDBACK_QUEUED", "IGNORED", "CONTROL");
    assertThat(reliableWorkers()).isEmpty();
  }

  @Test
  void recoversAReservedClaimBeforeMakingTheFirstPollRequest() {
    Path workspace = temporaryDirectory.resolve("recovery-workspace");
    Path database = workspace.resolve("channels/channel-ledger.db");
    var schema = new ChannelLedgerSchemaInitializer(database, 5_000);
    schema.initialize();
    var ledger = new JdbcChannelLedger(schema);
    ChannelInstanceId instance =
        TelegramChannelInstance.from(
                new TelegramBotToken("123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234"))
            .id();
    InboundMessage inbound =
        new InboundMessage(
            MessageContract.CURRENT_VERSION,
            "telegram:10001:30",
            "turn-reserved",
            "telegram:10001",
            new MessageRoute("telegram", "10001"),
            "10001",
            "not-persisted",
            Instant.parse("2026-07-16T00:00:00Z"));
    String requestFingerprint = ChannelFingerprint.request(inbound);
    String eventFingerprint =
        ChannelFingerprint.event(instance, "30", 30, InboxEventKind.TURN, "", requestFingerprint);
    ledger.recordEvent(
        new ChannelLedgerCommand.RecordEvent(
            instance,
            "30",
            30,
            eventFingerprint,
            InboxEventKind.TURN,
            "",
            requestFingerprint,
            new ChannelLedgerCommand.TurnReservation(
                inbound.messageId(), requestFingerprint, inbound.turnId()),
            null,
            Instant.parse("2026-07-16T00:00:00Z")));

    var recoveredBeforePoll = new AtomicBoolean();
    var api = new ScriptedApi();
    api.beforePoll = () -> recoveredBeforePoll.set("START_RETRYABLE".equals(claimState(database)));
    TelegramReliableChannelAdapter adapter = adapter(workspace, api);

    try {
      adapter.start();
      await(() -> !api.requestedOffsets.isEmpty(), "恢复后的首次 Poll");
      assertThat(recoveredBeforePoll).isTrue();
      assertThat(api.requestedOffsets.getFirst()).isZero();
    } finally {
      adapter.close();
    }
  }

  @Test
  void shutdownDoesNotReleaseRuntimeOwnershipWhileDeliveryWorkerIsStillAlive() throws Exception {
    Path workspace = temporaryDirectory.resolve("blocked-delivery-workspace");
    var api = new ScriptedApi();
    var sendEntered = new CountDownLatch(1);
    var releaseSend = new CountDownLatch(1);
    api.beforeSend =
        () -> {
          sendEntered.countDown();
          awaitUninterruptibly(releaseSend);
        };
    api.enqueue(List.of(update(40, 4, "blocked delivery")));
    var reliability =
        new ChannelReliabilityProperties(
            ChannelReliabilityMode.SQLITE, 10, 10, Duration.ofDays(30), 1_000, 100);
    var runtime = new ChannelReliabilityRuntime(workspace, reliability, Clock.systemUTC());
    var token = new TelegramBotToken("123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234");
    var instance = TelegramChannelInstance.from(token);
    var threads = new TrackingThreadStarter();
    TelegramReliableChannelAdapter adapter =
        new TelegramReliableChannelAdapter(
            api,
            new TelegramUpdateMapper(Set.of(10001L), () -> "blocked-turn"),
            new MessageTurnService(new AnsweringChat()),
            telegramProperties(Set.of(10001L), Duration.ofMillis(100)),
            instance,
            runtime,
            threads,
            duration -> {});

    adapter.start();
    try {
      assertThat(sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(adapter::close)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Telegram 可靠渠道未能在期限内停止");

      ChannelReliabilityRuntime.Session reopened = null;
      Throwable restartFailure = null;
      try {
        reopened = runtime.start(instance.id());
      } catch (Throwable failure) {
        restartFailure = failure;
      } finally {
        if (reopened != null) {
          reopened.close();
        }
      }
      assertThat(restartFailure).isInstanceOf(IllegalStateException.class);
    } finally {
      releaseSend.countDown();
      await(threads::allStopped, "阻塞投递 Worker 退出");
      adapter.close();
    }
  }

  private static TelegramReliableChannelAdapter adapter(Path workspace, ScriptedApi api) {
    return adapter(workspace, api, new AnsweringChat(), Set.of(10001L));
  }

  private static TelegramReliableChannelAdapter adapter(
      Path workspace, ScriptedApi api, ChatUseCase chat, Set<Long> allowed) {
    var ids = new AtomicLong();
    var reliability =
        new ChannelReliabilityProperties(
            ChannelReliabilityMode.SQLITE, 10, 10, Duration.ofDays(30), 1_000, 100);
    return new TelegramReliableChannelAdapter(
        api,
        new TelegramUpdateMapper(allowed, () -> "mapper-" + ids.incrementAndGet()),
        new MessageTurnService(chat),
        telegramProperties(allowed),
        TelegramChannelInstance.from(
            new TelegramBotToken("123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234")),
        new ChannelReliabilityRuntime(workspace, reliability, Clock.systemUTC()),
        ChannelThreadStarter.virtualThreads(),
        duration -> {});
  }

  private static TelegramProperties telegramProperties(Set<Long> allowed) {
    return telegramProperties(allowed, Duration.ofSeconds(2));
  }

  private static TelegramProperties telegramProperties(
      Set<Long> allowed, Duration shutdownTimeout) {
    return new TelegramProperties(
        true,
        allowed.stream().sorted().map(String::valueOf).toList(),
        1,
        4,
        Duration.ofSeconds(1),
        Duration.ofMillis(10),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(1),
        shutdownTimeout,
        Duration.ofMillis(10),
        Duration.ofSeconds(1));
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        return;
      } catch (InterruptedException failure) {
        interrupted = true;
      }
    }
  }

  private static TelegramUpdate update(long updateId, long messageId, String text) {
    return update(updateId, 10001, messageId, text);
  }

  private static TelegramUpdate update(long updateId, long chatId, long messageId, String text) {
    return new TelegramUpdate(
        updateId,
        new TelegramMessage(
            messageId,
            Instant.parse("2026-07-16T00:00:00Z"),
            chatId,
            "private",
            chatId,
            false,
            text));
  }

  private static List<String> reliableWorkers() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .map(Thread::getName)
        .filter(
            name ->
                name.equals("telegram-reliable-poll-worker")
                    || name.equals("channel-delivery-worker")
                    || name.equals("reliable-inbound-turn"))
        .toList();
  }

  private static boolean attemptStarted(Path database) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT outcome FROM channel_delivery_attempts ORDER BY started_at LIMIT 1")) {
      return rows.next() && "STARTED".equals(rows.getString(1)) && !rows.next();
    } catch (SQLException failure) {
      return false;
    }
  }

  private static List<String> decisions(Path database) throws SQLException {
    var decisions = new CopyOnWriteArrayList<String>();
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT decision FROM channel_inbox_events ORDER BY external_sequence")) {
      while (rows.next()) {
        decisions.add(rows.getString(1));
      }
    }
    return List.copyOf(decisions);
  }

  private static String claimState(Path database) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT state FROM channel_turn_claims LIMIT 1")) {
      return rows.next() ? rows.getString(1) : "";
    } catch (SQLException failure) {
      return "";
    }
  }

  private static void await(BooleanSupplier condition, String description) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
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
    private volatile Runnable beforePoll = () -> {};
    private volatile Runnable beforeSend = () -> {};

    private void enqueue(List<TelegramUpdate> updates) {
      batches.add(List.copyOf(updates));
    }

    @Override
    public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
      beforePoll.run();
      requestedOffsets.add(offset);
      try {
        return batches.take();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
      }
    }

    @Override
    public TelegramSendReceipt sendMessage(long chatId, String text) {
      beforeSend.run();
      sends.add(new Send(chatId, text));
      return new TelegramSendReceipt(sends.size());
    }
  }

  private static final class TrackingThreadStarter implements ChannelThreadStarter {
    private final List<Thread> threads = new CopyOnWriteArrayList<>();

    @Override
    public Thread start(String name, Runnable task) {
      Thread thread = Thread.ofVirtual().name(name).start(task);
      threads.add(thread);
      return thread;
    }

    private boolean allStopped() {
      return threads.stream().noneMatch(Thread::isAlive);
    }
  }

  private static final class CancellableChat implements ChatUseCase {
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResult chat(ChatCommand command) {
      return chat(command, TurnCancellation.none(), ChatProgressListener.noop());
    }

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      calls.incrementAndGet();
      started.countDown();
      var cancelled = new CountDownLatch(1);
      try (var registration = cancellation.onCancellation(cancelled::countDown)) {
        cancelled.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TurnCancelledException("测试 Turn 被中断");
      }
      throw new TurnCancelledException("测试 Turn 已取消");
    }
  }

  private static final class AnsweringChat implements ChatUseCase {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResult chat(ChatCommand command) {
      return answer(command);
    }

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      calls.incrementAndGet();
      progressListener.onContentDelta("volatile-preview");
      return answer(command);
    }

    private static ChatResult answer(ChatCommand command) {
      return new ChatResult(command.sessionId(), new ChatMessage(MessageRole.ASSISTANT, "answer"));
    }
  }
}
