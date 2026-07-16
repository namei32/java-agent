package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import io.namei.agent.adapter.sqlite.SqliteSchemaInitializer;
import io.namei.agent.application.ChatService;
import io.namei.agent.application.KeyedSessionExecutionGate;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityMode;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityProperties;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityRuntime;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.port.ChatModelPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class TelegramReliableDeliveryIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String FIRST_TOKEN = "123456789:FIRST_FAKE_TOKEN_1234567890";
  private static final String ROTATED_TOKEN = "123456789:ROTATED_FAKE_TOKEN_12345678";
  private static final String SECOND_TOKEN = "987654321:SECOND_FAKE_TOKEN_123456789";
  private static final long CHAT_ID = 10001;
  private static final String SESSION_ID = "telegram:" + CHAT_ID;
  private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(8);

  @TempDir Path temporaryDirectory;

  @AfterEach
  void releasesEveryReliableWorker() {
    assertThat(reliableWorkers()).isEmpty();
  }

  @Test
  void commitsTheConversationTerminalReceiptAndCursorThenRestartsWithoutRunningTheAgentAgain()
      throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("ordinary"))) {
      var model = new CountingModel("可靠回答");
      rig.server().enqueuePoll(200, update(100, 500, "普通问题"));
      rig.server().enqueueSend(200, sent(9001));
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model, Clock.fixed(NOW, ZoneOffset.UTC));

      first.start();
      await(() -> "DELIVERED".equals(value(rig.ledger(), "channel_deliveries", "state")), "终态送达");
      await(() -> first.adapter().nextOffset() == 101, "适配器观察持久 Cursor");

      assertThat(model.calls()).isEqualTo(1);
      assertThat(first.sessions().load(SESSION_ID).messages())
          .containsExactly(
              new ChatMessage(MessageRole.USER, "普通问题"),
              new ChatMessage(MessageRole.ASSISTANT, "可靠回答"));
      assertThat(value(rig.ledger(), "channel_turn_claims", "state"))
          .isEqualTo("TERMINAL_RECORDED");
      assertThat(value(rig.ledger(), "channel_delivery_parts", "remote_message_id"))
          .isEqualTo("9001");
      assertThat(values(rig.ledger(), "channel_delivery_attempts", "outcome", "attempt_number"))
          .containsExactly("SUCCEEDED");
      assertThat(first.adapter().nextOffset()).isEqualTo(101);
      first.close();

      int pollsBeforeRestart = pollRequests(rig.server()).size();
      AdapterHandle restarted =
          rig.adapter(ROTATED_TOKEN, model, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
      restarted.start();
      await(() -> pollRequests(rig.server()).size() > pollsBeforeRestart, "重启后使用持久 Cursor 发起 Poll");

      assertThat(lastPollOffset(rig.server())).isEqualTo(101);
      assertThat(model.calls()).isEqualTo(1);
      assertThat(sendRequests(rig.server())).hasSize(1);
      assertThat(restarted.adapter().instanceId()).isEqualTo(first.adapter().instanceId());
    }
  }

  @Test
  void concurrentRedeliveryOfTheSameUpdateCrossesTheAgentBoundaryOnlyOnce() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("concurrent"))) {
      var releaseUpdates = new CountDownLatch(1);
      String duplicate = update(200, 600, "并发重复问题");
      rig.server().enqueuePollWhen(releaseUpdates, 200, duplicate);
      rig.server().enqueuePollWhen(releaseUpdates, 200, duplicate);
      rig.server().enqueueSend(200, sent(9002));
      var model = new CountingModel("唯一回答");
      Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model, clock);
      AdapterHandle second = rig.adapter(ROTATED_TOKEN, model, clock);

      first.start();
      second.start();
      await(() -> pollRequests(rig.server()).size() >= 2, "两个并发 Poll 到达");
      releaseUpdates.countDown();
      await(() -> "DELIVERED".equals(value(rig.ledger(), "channel_deliveries", "state")), "唯一投递完成");

      assertThat(model.calls()).isEqualTo(1);
      assertThat(count(rig.ledger(), "channel_turn_claims")).isEqualTo(1);
      assertThat(count(rig.ledger(), "channel_deliveries")).isEqualTo(1);
      assertThat(sendRequests(rig.server())).hasSize(1);
      assertThat(first.sessions().load(SESSION_ID).messages()).hasSize(2);
      assertThat(first.adapter().nextOffset()).isEqualTo(201);
      assertThat(second.adapter().nextOffset()).isEqualTo(201);
    }
  }

  @Test
  void anOutboxCommittedBeforeTheDeliveryWorkerStartsIsOnlySentAfterRestart() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("outbox-restart"))) {
      rig.server().enqueuePoll(200, update(300, 700, "提交后再发送"));
      rig.server().enqueueSend(200, sent(9003));
      var model = new CountingModel("已持久终态");
      var heldDelivery = new HeldDeliveryStarter();
      AdapterHandle first =
          rig.adapter(FIRST_TOKEN, model, Clock.fixed(NOW, ZoneOffset.UTC), heldDelivery);

      first.start();
      await(
          () -> "PENDING".equals(value(rig.ledger(), "channel_deliveries", "state")),
          "Outbox 已提交但 Worker 未运行");

      assertThat(heldDelivery.heldWorkers()).isEqualTo(1);
      assertThat(value(rig.ledger(), "channel_turn_claims", "state"))
          .isEqualTo("TERMINAL_RECORDED");
      assertThat(sendRequests(rig.server())).isEmpty();
      assertThat(model.calls()).isEqualTo(1);
      first.close();

      AdapterHandle restarted =
          rig.adapter(ROTATED_TOKEN, model, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
      restarted.start();
      await(() -> "DELIVERED".equals(value(rig.ledger(), "channel_deliveries", "state")), "重启恢复投递");

      assertThat(sendRequests(rig.server())).hasSize(1);
      assertThat(model.calls()).isEqualTo(1);
      assertThat(restarted.sessions().load(SESSION_ID).messages()).hasSize(2);
    }
  }

  @Test
  void aRateLimitedPartKeepsItsDueTimeAndRetriesTheSamePayloadAfterRestart() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("rate-limit-restart"))) {
      rig.server().enqueuePoll(200, update(400, 800, "限流问题"));
      rig.server()
          .enqueueSend(429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":1}}");
      rig.server().enqueueSend(200, sent(9004));
      var clock = new MutableClock(NOW);
      var model = new CountingModel("限流后回答");
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model, clock);

      first.start();
      await(
          () -> "RETRY_WAIT".equals(value(rig.ledger(), "channel_delivery_parts", "state")),
          "429 Due Time 持久化");

      assertThat(sendRequests(rig.server())).hasSize(1);
      assertThat(values(rig.ledger(), "channel_delivery_attempts", "outcome", "attempt_number"))
          .containsExactly("RETRYABLE_REJECTED");
      first.close();

      clock.advance(Duration.ofSeconds(2));
      AdapterHandle restarted = rig.adapter(ROTATED_TOKEN, model, clock);
      restarted.start();
      await(
          () -> "DELIVERED".equals(value(rig.ledger(), "channel_deliveries", "state")),
          "到期 Part 重试成功");

      assertThat(sendRequests(rig.server())).hasSize(2);
      assertThat(sendTexts(rig.server())).containsExactly("限流后回答", "限流后回答");
      assertThat(values(rig.ledger(), "channel_delivery_attempts", "outcome", "attempt_number"))
          .containsExactly("RETRYABLE_REJECTED", "SUCCEEDED");
      assertThat(model.calls()).isEqualTo(1);
    }
  }

  @Test
  void aMultipartDeliveryStopsAfterAnUnknownSecondPartAndNeverResendsOnRestart() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("multipart-unknown"))) {
      String answer = "多".repeat(4_500);
      rig.server().enqueuePoll(200, update(500, 900, "长回答问题"));
      rig.server().enqueueSend(200, sent(9005));
      rig.server().enqueueSend(503, "temporary failure");
      var model = new CountingModel(answer);
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model, Clock.fixed(NOW, ZoneOffset.UTC));

      first.start();
      await(() -> "UNKNOWN".equals(value(rig.ledger(), "channel_deliveries", "state")), "第二分片结果未知");

      assertThat(sendRequests(rig.server())).hasSize(2);
      assertThat(values(rig.ledger(), "channel_delivery_parts", "state", "part_index"))
          .containsExactly("DELIVERED", "UNKNOWN");
      assertThat(values(rig.ledger(), "channel_delivery_attempts", "outcome", "part_index"))
          .containsExactly("SUCCEEDED", "UNKNOWN");
      assertThat(
              intValues(
                  rig.ledger(), "channel_delivery_parts", "length(payload_text)", "part_index"))
          .containsExactly(4_000, 500);
      first.close();

      int sendsBeforeRestart = sendRequests(rig.server()).size();
      int pollsBeforeRestart = pollRequests(rig.server()).size();
      AdapterHandle restarted =
          rig.adapter(ROTATED_TOKEN, model, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
      restarted.start();
      await(() -> pollRequests(rig.server()).size() > pollsBeforeRestart, "Unknown 恢复后的首次 Poll");

      assertThat(restarted.adapter().snapshot().reliability().unknownDeliveries()).isEqualTo(1);
      assertThat(sendRequests(rig.server())).hasSize(sendsBeforeRestart);
      assertThat(model.calls()).isEqualTo(1);
    }
  }

  @Test
  void differentBotIdsUseIsolatedCursorsClaimsAndDeliveriesInTheSameDatabase() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("bot-isolation"))) {
      var model = new CountingModel("实例隔离回答");
      rig.server().enqueuePoll(200, update(600, 1000, "第一 Bot 问题"));
      rig.server().enqueueSend(200, sent(9006));
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model, Clock.fixed(NOW, ZoneOffset.UTC));
      first.start();
      await(
          () -> countWhere(rig.ledger(), "channel_deliveries", "state = 'DELIVERED'") == 1,
          "第一 Bot 投递");
      first.close();

      rig.server().enqueuePoll(200, update(600, 1000, "第二 Bot 问题"));
      rig.server().enqueueSend(200, sent(9007));
      AdapterHandle second =
          rig.adapter(SECOND_TOKEN, model, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
      second.start();
      await(
          () -> countWhere(rig.ledger(), "channel_deliveries", "state = 'DELIVERED'") == 2,
          "第二 Bot 投递");

      assertThat(first.adapter().instanceId()).isNotEqualTo(second.adapter().instanceId());
      assertThat(countDistinct(rig.ledger(), "channel_cursors", "instance_id")).isEqualTo(2);
      assertThat(countDistinct(rig.ledger(), "channel_turn_claims", "instance_id")).isEqualTo(2);
      assertThat(countDistinct(rig.ledger(), "channel_deliveries", "instance_id")).isEqualTo(2);
      assertThat(longValues(rig.ledger(), "channel_cursors", "next_sequence", "instance_id"))
          .containsExactly(601L, 601L);
      assertThat(model.calls()).isEqualTo(2);
      assertThat(sendRequests(rig.server())).hasSize(2);
      assertThat(sendPaths(rig.server()))
          .containsExactlyInAnyOrder(
              "/bot" + FIRST_TOKEN + "/sendMessage", "/bot" + SECOND_TOKEN + "/sendMessage");
    }
  }

  private static String update(long updateId, long messageId, String text) {
    return JSON.writeValueAsString(
        Map.of(
            "ok",
            true,
            "result",
            List.of(
                Map.of(
                    "update_id",
                    updateId,
                    "message",
                    Map.of(
                        "message_id",
                        messageId,
                        "date",
                        NOW.getEpochSecond(),
                        "text",
                        text,
                        "chat",
                        Map.of("id", CHAT_ID, "type", "private"),
                        "from",
                        Map.of("id", CHAT_ID, "is_bot", false))))));
  }

  private static String sent(long messageId) {
    return "{\"ok\":true,\"result\":{\"message_id\":" + messageId + "}}";
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

  private static long lastPollOffset(TelegramBotApiStubServer server) {
    return JSON.readTree(pollRequests(server).getLast().body()).path("offset").asLong();
  }

  private static List<String> sendTexts(TelegramBotApiStubServer server) {
    return sendRequests(server).stream()
        .map(request -> JSON.readTree(request.body()).path("text").asString())
        .toList();
  }

  private static List<String> sendPaths(TelegramBotApiStubServer server) {
    return sendRequests(server).stream().map(TelegramBotApiStubServer.Request::path).toList();
  }

  private static String value(Path database, String table, String column) {
    List<String> values = values(database, table, column, "rowid");
    return values.isEmpty() ? "" : values.getFirst();
  }

  private static List<String> values(Path database, String table, String column, String orderBy) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " ORDER BY " + orderBy)) {
      var found = new ArrayList<String>();
      while (rows.next()) {
        found.add(rows.getString(1));
      }
      return List.copyOf(found);
    } catch (SQLException failure) {
      return List.of();
    }
  }

  private static List<Integer> intValues(
      Path database, String table, String column, String orderBy) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " ORDER BY " + orderBy)) {
      var found = new ArrayList<Integer>();
      while (rows.next()) {
        found.add(rows.getInt(1));
      }
      return List.copyOf(found);
    } catch (SQLException failure) {
      return List.of();
    }
  }

  private static List<Long> longValues(Path database, String table, String column, String orderBy) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " ORDER BY " + orderBy)) {
      var found = new ArrayList<Long>();
      while (rows.next()) {
        found.add(rows.getLong(1));
      }
      return List.copyOf(found);
    } catch (SQLException failure) {
      return List.of();
    }
  }

  private static long count(Path database, String table) {
    return countWhere(database, table, "1 = 1");
  }

  private static long countWhere(Path database, String table, String predicate) {
    return singleLong(database, "SELECT COUNT(*) FROM " + table + " WHERE " + predicate);
  }

  private static long countDistinct(Path database, String table, String column) {
    return singleLong(database, "SELECT COUNT(DISTINCT " + column + ") FROM " + table);
  }

  private static long singleLong(Path database, String query) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      return rows.next() ? rows.getLong(1) : -1;
    } catch (SQLException failure) {
      return -1;
    }
  }

  private static void await(BooleanSupplier condition, String description) {
    long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("等待超时: " + description);
      }
      Thread.onSpinWait();
    }
  }

  private static List<String> reliableWorkers() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .map(Thread::getName)
        .filter(
            name ->
                name.equals("telegram-reliable-poll-worker")
                    || name.equals("channel-delivery-worker")
                    || name.equals("reliable-inbound-turn")
                    || name.startsWith("telegram-fake-server-worker-"))
        .toList();
  }

  private static TelegramProperties telegramProperties() {
    return new TelegramProperties(
        true,
        List.of(Long.toString(CHAT_ID)),
        4,
        8,
        Duration.ofSeconds(1),
        Duration.ofMillis(10),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(20),
        Duration.ofSeconds(2),
        Duration.ofSeconds(2),
        Duration.ofMillis(10),
        Duration.ofSeconds(5));
  }

  private static ChannelReliabilityProperties reliabilityProperties() {
    return new ChannelReliabilityProperties(
        ChannelReliabilityMode.SQLITE, 10, 10, Duration.ofDays(30), 1_000, 100);
  }

  private record AdapterHandle(
      TelegramReliableChannelAdapter adapter, JdbcSessionRepository sessions)
      implements AutoCloseable {
    private void start() {
      adapter.start();
    }

    @Override
    public void close() {
      adapter.close();
    }
  }

  private static final class TestRig implements AutoCloseable {
    private final Path workspace;
    private final Path ledger;
    private final TelegramBotApiStubServer server;
    private final CountDownLatch blockedPolls = new CountDownLatch(1);
    private final List<AdapterHandle> adapters = new ArrayList<>();

    private TestRig(Path workspace) throws IOException {
      this.workspace = workspace;
      Files.createDirectories(workspace);
      this.ledger = workspace.resolve("channels/channel-ledger.db");
      this.server = new TelegramBotApiStubServer();
      server.respondToPollWhen(blockedPolls, 200, "{\"ok\":true,\"result\":[]}");
    }

    private TelegramBotApiStubServer server() {
      return server;
    }

    private Path ledger() {
      return ledger;
    }

    private AdapterHandle adapter(String tokenValue, CountingModel model, Clock clock) {
      return adapter(tokenValue, model, clock, ChannelThreadStarter.virtualThreads());
    }

    private AdapterHandle adapter(
        String tokenValue, CountingModel model, Clock clock, ChannelThreadStarter starter) {
      var token = new TelegramBotToken(tokenValue);
      var sessionSchema = new SqliteSchemaInitializer(workspace.resolve("sessions.db"), 5_000);
      sessionSchema.initialize();
      var sessions = new JdbcSessionRepository(sessionSchema);
      var chat =
          new ChatService(
              sessions,
              model,
              new ConversationHistorySelector(),
              new HistoryLimits(40, 100_000),
              new KeyedSessionExecutionGate(Duration.ofSeconds(1)),
              "系统提示",
              clock);
      TelegramProperties properties = telegramProperties();
      var adapter =
          new TelegramReliableChannelAdapter(
              new JdkTelegramBotApi(
                  server.baseUri(),
                  token,
                  properties.connectTimeout(),
                  properties.pollRequestTimeout(),
                  properties.sendRequestTimeout()),
              new TelegramUpdateMapper(Set.of(CHAT_ID), new SecureTelegramIdGenerator()),
              new MessageTurnService(chat),
              properties,
              TelegramChannelInstance.from(token),
              new ChannelReliabilityRuntime(workspace, reliabilityProperties(), clock),
              starter,
              duration -> {});
      var handle = new AdapterHandle(adapter, sessions);
      adapters.add(handle);
      return handle;
    }

    @Override
    public void close() {
      RuntimeException firstFailure = null;
      for (int index = adapters.size() - 1; index >= 0; index--) {
        try {
          adapters.get(index).close();
        } catch (RuntimeException failure) {
          if (firstFailure == null) {
            firstFailure = failure;
          }
        }
      }
      blockedPolls.countDown();
      try {
        server.close();
      } catch (RuntimeException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
      if (firstFailure != null) {
        throw firstFailure;
      }
    }
  }

  private static final class CountingModel implements ChatModelPort {
    private final String answer;
    private final AtomicInteger calls = new AtomicInteger();

    private CountingModel(String answer) {
      this.answer = answer;
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      calls.incrementAndGet();
      return new ChatModelResponse(answer);
    }

    private int calls() {
      return calls.get();
    }
  }

  private static final class HeldDeliveryStarter implements ChannelThreadStarter {
    private final AtomicInteger heldWorkers = new AtomicInteger();

    @Override
    public Thread start(String name, Runnable task) {
      if ("channel-delivery-worker".equals(name)) {
        heldWorkers.incrementAndGet();
        return Thread.ofPlatform().name(name).unstarted(task);
      }
      return Thread.ofVirtual().name(name).start(task);
    }

    private int heldWorkers() {
      return heldWorkers.get();
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;

    private MutableClock(Instant initial) {
      this.instant = new AtomicReference<>(initial);
    }

    private void advance(Duration duration) {
      instant.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("测试 Clock 只支持 UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
