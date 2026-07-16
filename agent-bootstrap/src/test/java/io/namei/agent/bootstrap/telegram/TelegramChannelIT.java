package io.namei.agent.bootstrap.telegram;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatProgressListener;
import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.TurnCancellation;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TelegramChannelIT {
  private static final String FAKE_TOKEN = "123456:TEST_TOKEN_12345678901234567890";
  private static final String EMPTY_UPDATES = "{\"ok\":true,\"result\":[]}";
  private static final String SENT = "{\"ok\":true,\"result\":{\"message_id\":43}}";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void carriesRealHttpThroughMessageTurnServiceAndSendsOnlyTheCorrectedTerminalAfter429()
      throws Exception {
    try (var server = server()) {
      var holdNextPoll = new CountDownLatch(1);
      server.respondToPollWhen(holdNextPoll, 200, EMPTY_UPDATES);
      server.enqueuePoll(200, updates(update(100, 10001, 1, "普通问题")));
      server.enqueueSend(
          429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":1}}");
      server.enqueueSend(200, SENT);
      var chat = new CorrectingChat();
      var sleeper = new RecordingSleeper();
      var threads = new RecordingThreadStarter();
      var adapter = adapter(server, chat, sleeper, threads, 2);

      try {
        adapter.start();
        await(() -> sendRequests(server).size() == 2, "429 后的单次重试");
        await(() -> adapter.snapshot().activeTurns() == 0, "普通 Turn 清理");

        assertThat(chat.calls).hasValue(1);
        assertThat(sendTexts(server)).containsExactly("权威最终回答", "权威最终回答");
        assertThat(sendTexts(server))
            .allSatisfy(
                text ->
                    assertThat(text)
                        .doesNotContain("preview-must-not-send", "tool_arguments", "tool_result"));
        assertThat(sleeper.durations).containsExactly(Duration.ofSeconds(1));
        assertThat(pollOffsets(server)).startsWith(0L, 101L);
        assertThat(adapter.nextOffset()).isEqualTo(101);
      } finally {
        close(adapter);
      }

      assertStopped(adapter, threads, 2);
    }
  }

  @Test
  void cancelTargetsOneConversationWhileAnotherRealHttpTurnStaysAlive() throws Exception {
    try (var server = server()) {
      var holdNextPoll = new CountDownLatch(1);
      server.respondToPollWhen(holdNextPoll, 200, EMPTY_UPDATES);
      server.enqueuePoll(
          200,
          updates(
              update(200, 10001, 1, "第一问"),
              update(201, 10002, 1, "第二问"),
              update(202, 10001, 2, "/cancel")));
      var chat = new BlockingChat("telegram:10001", "telegram:10002");
      var threads = new RecordingThreadStarter();
      var adapter = adapter(server, chat, new RecordingSleeper(), threads, 2);

      try {
        adapter.start();
        assertThat(chat.awaitStarted("telegram:10001")).isTrue();
        assertThat(chat.awaitStarted("telegram:10002")).isTrue();
        await(() -> sendTexts(server).contains("请求已取消（REQUESTED）"), "目标会话取消终态");

        assertThat(chat.cancelledReasons)
            .containsEntry("telegram:10001", TurnCancellationCode.REQUESTED)
            .doesNotContainKey("telegram:10002");
        assertThat(chat.commands)
            .extracting(ChatCommand::message)
            .containsExactlyInAnyOrder("第一问", "第二问")
            .doesNotContain("/cancel");
        assertThat(sendBodies(server)).filteredOn(body -> body.chatId() == 10002).isEmpty();

        chat.release("telegram:10002");
        await(
            () -> sendBodies(server).contains(new SentBody(10002, "answer-telegram:10002")),
            "另一会话权威终态");
        await(() -> adapter.snapshot().activeTurns() == 0, "两个 Turn 清理");
        assertThat(adapter.nextOffset()).isEqualTo(203);
      } finally {
        close(adapter);
      }

      assertStopped(adapter, threads, 2);
    }
  }

  @Test
  void pollRetryExhaustionDisconnectsTheActiveHttpTurnAtTheSameOffset() throws Exception {
    try (var server = server()) {
      var releaseFailures = new CountDownLatch(1);
      server.enqueuePoll(200, updates(update(300, 10001, 1, "等待断开")));
      server.enqueuePollWhen(releaseFailures, 503, "remote-body-must-not-leak");
      server.enqueuePoll(503, "remote-body-must-not-leak");
      server.enqueuePoll(503, "remote-body-must-not-leak");
      var chat = new BlockingChat("telegram:10001");
      var sleeper = new RecordingSleeper();
      var threads = new RecordingThreadStarter();
      var adapter = adapter(server, chat, sleeper, threads, 1);

      try {
        adapter.start();
        assertThat(chat.awaitStarted("telegram:10001")).isTrue();
        releaseFailures.countDown();
        await(() -> adapter.snapshot().state() == ChannelState.FAILED, "Poll 重试耗尽");
        await(() -> adapter.snapshot().activeTurns() == 0, "断开的 Turn 清理");

        assertThat(adapter.snapshot().code()).isEqualTo("POLL_RETRY_EXHAUSTED");
        assertThat(adapter.snapshot().consecutiveFailures()).isEqualTo(3);
        assertThat(chat.cancelledReasons)
            .containsEntry("telegram:10001", TurnCancellationCode.CHANNEL_DISCONNECTED);
        assertThat(pollOffsets(server)).containsExactly(0L, 301L, 301L, 301L);
        assertThat(sleeper.durations)
            .containsExactly(PROPERTIES.retryBackoff(), PROPERTIES.retryBackoff());
        assertThat(sendRequests(server)).isEmpty();
        assertThat(adapter.availableTurnPermits()).isEqualTo(1);
      } finally {
        close(adapter);
      }

      assertStopped(adapter, threads, 1);
    }
  }

  @Test
  void shutdownInterruptsTheRealLongPollAndJoinsEveryTurnWorker() throws Exception {
    try (var server = server()) {
      var holdNextPoll = new CountDownLatch(1);
      server.respondToPollWhen(holdNextPoll, 200, EMPTY_UPDATES);
      server.enqueuePoll(200, updates(update(400, 10001, 1, "等待关闭")));
      var chat = new BlockingChat("telegram:10001");
      var threads = new RecordingThreadStarter();
      var adapter = adapter(server, chat, new RecordingSleeper(), threads, 1);

      try {
        adapter.start();
        assertThat(chat.awaitStarted("telegram:10001")).isTrue();
        await(() -> pollRequests(server).size() >= 2, "阻塞中的真实 Long Poll");

        adapter.close();

        assertThat(chat.cancelledReasons)
            .containsEntry("telegram:10001", TurnCancellationCode.SHUTDOWN);
        assertThat(sendRequests(server)).isEmpty();
      } finally {
        close(adapter);
      }
      assertStopped(adapter, threads, 1);
    }
  }

  private static TelegramChannelAdapter adapter(
      TelegramBotApiStubServer server,
      ChatUseCase chat,
      ChannelSleeper sleeper,
      ChannelThreadStarter threads,
      int maxConcurrentTurns) {
    TelegramProperties properties = properties(maxConcurrentTurns);
    var ids = new AtomicLong();
    return new TelegramChannelAdapter(
        new JdkTelegramBotApi(
            server.baseUri(),
            new TelegramBotToken(FAKE_TOKEN),
            properties.connectTimeout(),
            properties.pollRequestTimeout(),
            properties.sendRequestTimeout()),
        new TelegramUpdateMapper(
            properties.allowedUserIds(), () -> "turn-loopback-" + ids.incrementAndGet()),
        new MessageTurnService(chat),
        properties,
        threads,
        sleeper);
  }

  private static TelegramProperties properties(int maxConcurrentTurns) {
    return new TelegramProperties(
        true,
        List.of("10001", "10002"),
        maxConcurrentTurns,
        8,
        Duration.ofSeconds(1),
        Duration.ofMillis(20),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ofSeconds(2),
        Duration.ofSeconds(3),
        Duration.ofMillis(1),
        Duration.ofSeconds(2));
  }

  private static final TelegramProperties PROPERTIES = properties(1);

  private static void assertStopped(
      TelegramChannelAdapter adapter, RecordingThreadStarter threads, int expectedPermits) {
    assertThat(adapter.snapshot().state()).isEqualTo(ChannelState.STOPPED);
    assertThat(adapter.snapshot().activeTurns()).isZero();
    assertThat(adapter.availableTurnPermits()).isEqualTo(expectedPermits);
    assertThat(threads.allStopped()).isTrue();
  }

  private static void close(TelegramChannelAdapter adapter) {
    if (adapter.snapshot().state() != ChannelState.STOPPED) {
      adapter.close();
    }
  }

  private static List<TelegramBotApiStubServer.Request> pollRequests(
      TelegramBotApiStubServer server) {
    return server.requests().stream()
        .filter(request -> request.path().endsWith("/getUpdates"))
        .toList();
  }

  private static List<TelegramBotApiStubServer.Request> sendRequests(
      TelegramBotApiStubServer server) {
    return server.requests().stream()
        .filter(request -> request.path().endsWith("/sendMessage"))
        .toList();
  }

  private static List<Long> pollOffsets(TelegramBotApiStubServer server) {
    return pollRequests(server).stream()
        .map(request -> JSON.readTree(request.body()).path("offset").asLong())
        .toList();
  }

  private static List<SentBody> sendBodies(TelegramBotApiStubServer server) {
    return sendRequests(server).stream()
        .map(
            request -> {
              var body = JSON.readTree(request.body());
              return new SentBody(body.path("chat_id").asLong(), body.path("text").asString());
            })
        .toList();
  }

  private static List<String> sendTexts(TelegramBotApiStubServer server) {
    return sendBodies(server).stream().map(SentBody::text).toList();
  }

  private static String updates(Incoming... updates) {
    List<Map<String, Object>> result =
        Arrays.stream(updates)
            .map(
                update ->
                    Map.<String, Object>of(
                        "update_id",
                        update.updateId(),
                        "message",
                        Map.of(
                            "message_id",
                            update.messageId(),
                            "date",
                            1784160000L,
                            "chat",
                            Map.of("id", update.chatId(), "type", "private"),
                            "from",
                            Map.of("id", update.chatId(), "is_bot", false),
                            "text",
                            update.text())))
            .collect(Collectors.toList());
    return JSON.writeValueAsString(Map.of("ok", true, "result", result));
  }

  private static Incoming update(long updateId, long chatId, long messageId, String text) {
    return new Incoming(updateId, chatId, messageId, text);
  }

  private static TelegramBotApiStubServer server() {
    try {
      return new TelegramBotApiStubServer();
    } catch (IOException failure) {
      throw new AssertionError("无法启动 Loopback Fake Telegram Server", failure);
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

  private record Incoming(long updateId, long chatId, long messageId, String text) {}

  private record SentBody(long chatId, String text) {}

  private static final class CorrectingChat implements ChatUseCase {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResult chat(ChatCommand command) {
      return answer(command, "权威最终回答");
    }

    @Override
    public ChatResult chat(
        ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
      calls.incrementAndGet();
      progressListener.onContentDelta("preview-must-not-send");
      progressListener.onContentDelta("{\"tool_arguments\":\"must-not-send\"}");
      progressListener.onContentDelta("{\"tool_result\":\"must-not-send\"}");
      return answer(command, "权威最终回答");
    }
  }

  private static final class BlockingChat implements ChatUseCase {
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    private final List<ChatCommand> commands = new CopyOnWriteArrayList<>();
    private final Map<String, TurnCancellationCode> cancelledReasons = new ConcurrentHashMap<>();

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
      commands.add(command);
      Gate gate = gates.get(command.sessionId());
      if (gate == null) {
        throw new IllegalStateException("缺少 Loopback Gate");
      }
      gate.started.countDown();
      try (var registration = cancellation.onCancellation(gate.release::countDown)) {
        gate.release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TurnCancelledException("Loopback Turn 被中断");
      }
      if (cancellation.isCancellationRequested()) {
        cancelledReasons.put(command.sessionId(), cancellation.reason());
        throw new TurnCancelledException("Loopback Turn 已取消");
      }
      return answer(command, "answer-" + command.sessionId());
    }

    private boolean awaitStarted(String session) throws InterruptedException {
      return gates.get(session).started.await(2, SECONDS);
    }

    private void release(String session) {
      gates.get(session).release.countDown();
    }

    private static final class Gate {
      private final CountDownLatch started = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
    }
  }

  private static ChatResult answer(ChatCommand command, String text) {
    return new ChatResult(command.sessionId(), new ChatMessage(MessageRole.ASSISTANT, text));
  }

  private static final class RecordingSleeper implements ChannelSleeper {
    private final List<Duration> durations = new CopyOnWriteArrayList<>();

    @Override
    public void sleep(Duration duration) {
      durations.add(duration);
    }
  }

  private static final class RecordingThreadStarter implements ChannelThreadStarter {
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
}
