package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import io.namei.agent.adapter.sqlite.SqliteSchemaInitializer;
import io.namei.agent.application.ChatService;
import io.namei.agent.application.KeyedSessionExecutionGate;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.bootstrap.channel.ChannelState;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Tag("failure")
class TelegramReliableDeliveryFailureIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String FIRST_TOKEN = "123456789:FIRST_FAKE_TOKEN_1234567890";
  private static final String ROTATED_TOKEN = "123456789:ROTATED_FAKE_TOKEN_12345678";
  private static final long CHAT_ID = 10001;
  private static final String SESSION_ID = "telegram:" + CHAT_ID;
  private static final Instant NOW = Instant.parse("2026-07-16T13:00:00Z");
  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(8);

  @TempDir Path temporaryDirectory;

  @AfterEach
  void releasesEveryReliableWorker() {
    assertThat(reliableWorkers()).isEmpty();
  }

  @Test
  void aClaimCommitFailureRollsBackTheClaimEventAndCursorBeforeTheAgentBoundary() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("claim-before-commit"))) {
      var release = new CountDownLatch(1);
      rig.server().enqueuePollWhen(release, 200, update(100, 500, "Claim 提交前"));
      var model = new CountingModel("不应执行");
      AdapterHandle adapter = rig.adapter(FIRST_TOKEN, model);

      adapter.start();
      await(() -> pollRequests(rig.server()).size() == 1, "首个 Poll 已阻塞");
      createTrigger(
          rig.ledger(),
          """
          CREATE TRIGGER fail_claim_commit
          BEFORE INSERT ON channel_inbox_events
          BEGIN
            SELECT RAISE(ABORT, 'injected claim commit failure');
          END
          """);
      release.countDown();
      await(() -> adapter.adapter().snapshot().state() == ChannelState.FAILED, "Claim 事务失败");

      assertThat(model.calls()).isZero();
      assertThat(count(rig.ledger(), "channel_turn_claims")).isZero();
      assertThat(count(rig.ledger(), "channel_inbox_events")).isZero();
      assertThat(count(rig.ledger(), "channel_cursors")).isZero();
      assertThat(sendRequests(rig.server())).isEmpty();
      assertThat(adapter.sessions().load(SESSION_ID).messages()).isEmpty();
    }
  }

  @Test
  void aCommittedReservationSurvivesAStartGapAndExecutesOnlyOnceAfterRestart() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("claim-after-commit"))) {
      rig.server().enqueuePoll(200, update(110, 510, "Claim 提交后"));
      var model = new CountingModel("恢复后回答");
      var held = new HeldTurnStarter();
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model, held, UnaryOperator.identity());

      first.start();
      await(
          () -> "RESERVED".equals(value(rig.ledger(), "channel_turn_claims", "state")),
          "Claim 已提交");

      await(() -> held.heldTurns() == 1, "已进入任务启动间隙");
      assertThat(model.calls()).isZero();
      assertThat(count(rig.ledger(), "channel_cursors")).isZero();
      first.close();

      rig.server().enqueuePoll(200, update(110, 510, "Claim 提交后"));
      rig.server().enqueueSend(200, sent(9101));
      AdapterHandle restarted = rig.adapter(ROTATED_TOKEN, model);
      restarted.start();
      await(
          () -> "DELIVERED".equals(value(rig.ledger(), "channel_deliveries", "state")),
          "保留 Claim 恢复");

      assertThat(model.calls()).isOne();
      assertThat(count(rig.ledger(), "channel_turn_claims")).isOne();
      assertThat(count(rig.ledger(), "channel_inbox_events")).isOne();
      assertThat(value(rig.ledger(), "channel_cursors", "next_sequence")).isEqualTo("111");
      assertThat(sendRequests(rig.server())).hasSize(1);
    }
  }

  @Test
  void aRunningCommitFailureRollsBackTheCursorAndRetriesTheReservedClaimAfterRestart()
      throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("running-before-commit"))) {
      var release = new CountDownLatch(1);
      rig.server().enqueuePollWhen(release, 200, update(120, 520, "Running 提交前"));
      var model = new CountingModel("事务恢复回答");
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model);

      first.start();
      await(() -> pollRequests(rig.server()).size() == 1, "首个 Poll 已阻塞");
      createTrigger(
          rig.ledger(),
          """
          CREATE TRIGGER fail_running_commit
          BEFORE UPDATE OF state ON channel_turn_claims
          WHEN NEW.state = 'RUNNING'
          BEGIN
            SELECT RAISE(ABORT, 'injected running commit failure');
          END
          """);
      release.countDown();
      await(
          () -> "RESERVED".equals(value(rig.ledger(), "channel_turn_claims", "state")),
          "Running 事务回滚");

      assertThat(model.calls()).isZero();
      assertThat(count(rig.ledger(), "channel_cursors")).isZero();
      first.close();
      dropTrigger(rig.ledger(), "fail_running_commit");

      rig.server().enqueuePoll(200, update(120, 520, "Running 提交前"));
      rig.server().enqueueSend(200, sent(9102));
      AdapterHandle restarted = rig.adapter(ROTATED_TOKEN, model);
      restarted.start();
      await(
          () -> "DELIVERED".equals(value(rig.ledger(), "channel_deliveries", "state")),
          "Running 边界恢复");

      assertThat(model.calls()).isOne();
      assertThat(value(rig.ledger(), "channel_cursors", "next_sequence")).isEqualTo("121");
      assertThat(sendRequests(rig.server())).hasSize(1);
    }
  }

  @Test
  void theRunningCommitAndCursorAreDurableBeforeTheAgentCanComplete() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("running-after-commit"))) {
      rig.server().enqueuePoll(200, update(130, 530, "Running 提交后"));
      rig.server().enqueueSend(200, sent(9103));
      var model = new BlockingModel("提交后回答");
      AdapterHandle adapter = rig.adapter(FIRST_TOKEN, model);

      adapter.start();
      assertThat(model.awaitEntered()).isTrue();
      await(
          () -> "RUNNING".equals(value(rig.ledger(), "channel_turn_claims", "state")),
          "Running 已提交");

      assertThat(value(rig.ledger(), "channel_cursors", "next_sequence")).isEqualTo("131");
      assertThat(count(rig.ledger(), "channel_deliveries")).isZero();
      assertThat(adapter.sessions().load(SESSION_ID).messages()).isEmpty();
      assertThat(sendRequests(rig.server())).isEmpty();

      model.release();
      await(
          () -> "DELIVERED".equals(value(rig.ledger(), "channel_deliveries", "state")),
          "Running 后完成");
      assertThat(adapter.sessions().load(SESSION_ID).messages())
          .containsExactly(
              new ChatMessage(MessageRole.USER, "Running 提交后"),
              new ChatMessage(MessageRole.ASSISTANT, "提交后回答"));
    }
  }

  @Test
  void aTerminalCommitFailureKeepsTheConversationButRecoversExecutionAsUnknownWithoutRerun()
      throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("terminal-before-commit"))) {
      var release = new CountDownLatch(1);
      rig.server().enqueuePollWhen(release, 200, update(140, 540, "Terminal 提交前"));
      var model = new CountingModel("会话已提交");
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model);

      first.start();
      await(() -> pollRequests(rig.server()).size() == 1, "首个 Poll 已阻塞");
      createTrigger(
          rig.ledger(),
          """
          CREATE TRIGGER fail_terminal_commit
          BEFORE INSERT ON channel_deliveries
          BEGIN
            SELECT RAISE(ABORT, 'injected terminal commit failure');
          END
          """);
      release.countDown();
      await(() -> first.sessions().load(SESSION_ID).messages().size() == 2, "Conversation 已提交");

      assertThat(model.calls()).isOne();
      assertThat(value(rig.ledger(), "channel_turn_claims", "state")).isEqualTo("RUNNING");
      assertThat(value(rig.ledger(), "channel_cursors", "next_sequence")).isEqualTo("141");
      assertThat(count(rig.ledger(), "channel_deliveries")).isZero();
      assertThat(sendRequests(rig.server())).isEmpty();
      first.close();
      dropTrigger(rig.ledger(), "fail_terminal_commit");

      int pollsBeforeRestart = pollRequests(rig.server()).size();
      AdapterHandle restarted = rig.adapter(ROTATED_TOKEN, model);
      restarted.start();
      await(() -> pollRequests(rig.server()).size() > pollsBeforeRestart, "Unknown 恢复后的 Poll");

      assertThat(value(rig.ledger(), "channel_turn_claims", "state"))
          .isEqualTo("EXECUTION_UNKNOWN");
      assertThat(restarted.adapter().snapshot().reliability().unknownExecutions()).isOne();
      assertThat(model.calls()).isOne();
      assertThat(sendRequests(rig.server())).isEmpty();
      assertThat(restarted.sessions().load(SESSION_ID).messages()).hasSize(2);
    }
  }

  @Test
  void anAttemptIsCommittedBeforeHttpAndACrashNeverCausesAnAutomaticSend() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("attempt-before-http"))) {
      rig.server().enqueuePoll(200, update(150, 550, "HTTP 前崩溃"));
      var model = new CountingModel("不得自动发送");
      var crash = new CrashBeforeSendApi(rig.ledger());
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model, new QuietVirtualStarter(), crash::wrap);

      first.start();
      assertThat(crash.awaitCrash()).isTrue();

      assertThat(crash.attemptWasDurable()).isTrue();
      assertThat(value(rig.ledger(), "channel_deliveries", "state")).isEqualTo("DELIVERING");
      assertThat(value(rig.ledger(), "channel_delivery_attempts", "outcome")).isEqualTo("STARTED");
      assertThat(sendRequests(rig.server())).isEmpty();
      first.close();

      int pollsBeforeRestart = pollRequests(rig.server()).size();
      AdapterHandle restarted = rig.adapter(ROTATED_TOKEN, model);
      restarted.start();
      await(() -> pollRequests(rig.server()).size() > pollsBeforeRestart, "Attempt 恢复后的 Poll");

      assertThat(value(rig.ledger(), "channel_deliveries", "state")).isEqualTo("UNKNOWN");
      assertThat(value(rig.ledger(), "channel_delivery_attempts", "outcome")).isEqualTo("UNKNOWN");
      assertThat(sendRequests(rig.server())).isEmpty();
      assertThat(model.calls()).isOne();
    }
  }

  @Test
  void httpSuccessBeforeTheSecondPartResultCommitBecomesUnknownAndNeverResendsEitherPart()
      throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("result-before-commit"))) {
      var release = new CountDownLatch(1);
      rig.server().enqueuePollWhen(release, 200, update(160, 560, "分片 Result 提交前"));
      rig.server().enqueueSend(200, sent(9104));
      rig.server().enqueueSend(200, sent(9105));
      var model = new CountingModel("多".repeat(4_500));
      AdapterHandle first = rig.adapter(FIRST_TOKEN, model);

      first.start();
      await(() -> pollRequests(rig.server()).size() == 1, "首个 Poll 已阻塞");
      createTrigger(
          rig.ledger(),
          """
          CREATE TRIGGER fail_second_part_result_commit
          BEFORE UPDATE OF outcome ON channel_delivery_attempts
          WHEN OLD.part_index = 1 AND OLD.outcome = 'STARTED' AND NEW.outcome = 'SUCCEEDED'
          BEGIN
            SELECT RAISE(ABORT, 'injected delivery result commit failure');
          END
          """);
      release.countDown();
      await(
          () ->
              sendRequests(rig.server()).size() == 2
                  && values(rig.ledger(), "channel_delivery_attempts", "outcome", "part_index")
                      .equals(List.of("SUCCEEDED", "STARTED")),
          "第二分片 HTTP 成功且 Result 回滚");

      assertThat(value(rig.ledger(), "channel_deliveries", "state")).isEqualTo("DELIVERING");
      assertThat(values(rig.ledger(), "channel_delivery_parts", "state", "part_index"))
          .containsExactly("DELIVERED", "IN_FLIGHT");
      assertThat(sendTexts(rig.server())).containsExactly("多".repeat(4_000), "多".repeat(500));
      first.close();
      dropTrigger(rig.ledger(), "fail_second_part_result_commit");

      int sendsBeforeRestart = sendRequests(rig.server()).size();
      int pollsBeforeRestart = pollRequests(rig.server()).size();
      AdapterHandle restarted = rig.adapter(ROTATED_TOKEN, model);
      restarted.start();
      await(() -> pollRequests(rig.server()).size() > pollsBeforeRestart, "Result 恢复后的 Poll");

      assertThat(value(rig.ledger(), "channel_deliveries", "state")).isEqualTo("UNKNOWN");
      assertThat(values(rig.ledger(), "channel_delivery_parts", "state", "part_index"))
          .containsExactly("DELIVERED", "UNKNOWN");
      assertThat(sendRequests(rig.server())).hasSize(sendsBeforeRestart);
      assertThat(model.calls()).isOne();
    }
  }

  @Test
  void shutdownInterruptsAnAgentPastTheRunningBoundaryAndReleasesEveryWorker() throws Exception {
    try (var rig = new TestRig(temporaryDirectory.resolve("shutdown"))) {
      rig.server().enqueuePoll(200, update(170, 570, "关闭中的 Agent"));
      var model = new InterruptibleModel();
      AdapterHandle adapter =
          rig.adapter(
              FIRST_TOKEN,
              model,
              ChannelThreadStarter.virtualThreads(),
              UnaryOperator.identity(),
              Duration.ofMillis(400));

      adapter.start();
      assertThat(model.awaitEntered()).isTrue();
      await(
          () -> "RUNNING".equals(value(rig.ledger(), "channel_turn_claims", "state")),
          "关闭前 Running");

      adapter.close();

      assertThat(model.wasInterrupted()).isTrue();
      assertThat(adapter.adapter().snapshot().state()).isEqualTo(ChannelState.STOPPED);
      assertThat(adapter.sessions().load(SESSION_ID).messages()).isEmpty();
      await(() -> reliableWorkers().isEmpty(), "可靠 Worker 释放");
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

  private static void createTrigger(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void dropTrigger(Path database, String name) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER " + name);
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

  private static List<String> sendTexts(TelegramBotApiStubServer server) {
    return sendRequests(server).stream()
        .map(request -> JSON.readTree(request.body()).path("text").asString())
        .toList();
  }

  private static String value(Path database, String table, String column) {
    List<String> found = values(database, table, column, "rowid");
    return found.isEmpty() ? "" : found.getFirst();
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

  private static long count(Path database, String table) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      return rows.next() ? rows.getLong(1) : -1;
    } catch (SQLException failure) {
      return -1;
    }
  }

  private static boolean attemptStarted(Path database) {
    return "STARTED".equals(value(database, "channel_delivery_attempts", "outcome"));
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

  private static TelegramProperties telegramProperties(Duration shutdownTimeout) {
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
        shutdownTimeout,
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

    private AdapterHandle adapter(String tokenValue, ChatModelPort model) {
      return adapter(
          tokenValue,
          model,
          ChannelThreadStarter.virtualThreads(),
          UnaryOperator.identity(),
          Duration.ofSeconds(2));
    }

    private AdapterHandle adapter(
        String tokenValue,
        ChatModelPort model,
        ChannelThreadStarter starter,
        UnaryOperator<TelegramBotApi> apiDecorator) {
      return adapter(tokenValue, model, starter, apiDecorator, Duration.ofSeconds(2));
    }

    private AdapterHandle adapter(
        String tokenValue,
        ChatModelPort model,
        ChannelThreadStarter starter,
        UnaryOperator<TelegramBotApi> apiDecorator,
        Duration shutdownTimeout) {
      var token = new TelegramBotToken(tokenValue);
      var sessionSchema = new SqliteSchemaInitializer(workspace.resolve("sessions.db"), 5_000);
      sessionSchema.initialize();
      var sessions = new JdbcSessionRepository(sessionSchema);
      Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
      var chat =
          new ChatService(
              sessions,
              model,
              new ConversationHistorySelector(),
              new HistoryLimits(40, 100_000),
              new KeyedSessionExecutionGate(Duration.ofSeconds(1)),
              "系统提示",
              clock);
      TelegramProperties properties = telegramProperties(shutdownTimeout);
      TelegramBotApi baseApi =
          new JdkTelegramBotApi(
              server.baseUri(),
              token,
              properties.connectTimeout(),
              properties.pollRequestTimeout(),
              properties.sendRequestTimeout());
      var adapter =
          new TelegramReliableChannelAdapter(
              apiDecorator.apply(baseApi),
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

  private static final class BlockingModel implements ChatModelPort {
    private final String answer;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    private BlockingModel(String answer) {
      this.answer = answer;
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      entered.countDown();
      try {
        released.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("测试模型被中断");
      }
      return new ChatModelResponse(answer);
    }

    private boolean awaitEntered() throws InterruptedException {
      return entered.await(2, TimeUnit.SECONDS);
    }

    private void release() {
      released.countDown();
    }
  }

  private static final class InterruptibleModel implements ChatModelPort {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final AtomicBoolean interrupted = new AtomicBoolean();

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
      entered.countDown();
      try {
        new CountDownLatch(1).await();
        throw new AssertionError("不可到达");
      } catch (InterruptedException expected) {
        interrupted.set(true);
        Thread.currentThread().interrupt();
        throw new IllegalStateException("测试模型按关闭信号退出");
      }
    }

    private boolean awaitEntered() throws InterruptedException {
      return entered.await(2, TimeUnit.SECONDS);
    }

    private boolean wasInterrupted() {
      return interrupted.get();
    }
  }

  private static final class HeldTurnStarter implements ChannelThreadStarter {
    private final AtomicInteger heldTurns = new AtomicInteger();

    @Override
    public Thread start(String name, Runnable task) {
      if ("reliable-inbound-turn".equals(name)) {
        heldTurns.incrementAndGet();
        return Thread.ofPlatform().name(name).unstarted(task);
      }
      return Thread.ofVirtual().name(name).start(task);
    }

    private int heldTurns() {
      return heldTurns.get();
    }
  }

  private static final class QuietVirtualStarter implements ChannelThreadStarter {
    @Override
    public Thread start(String name, Runnable task) {
      return Thread.ofVirtual()
          .name(name)
          .uncaughtExceptionHandler((thread, failure) -> {})
          .start(task);
    }
  }

  private static final class CrashBeforeSendApi {
    private final Path ledger;
    private final CountDownLatch crashed = new CountDownLatch(1);
    private final AtomicBoolean attemptWasDurable = new AtomicBoolean();

    private CrashBeforeSendApi(Path ledger) {
      this.ledger = ledger;
    }

    private TelegramBotApi wrap(TelegramBotApi delegate) {
      return new TelegramBotApi() {
        @Override
        public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
          return delegate.getUpdates(offset, longPollTimeout);
        }

        @Override
        public TelegramSendReceipt sendMessage(long chatId, String text) {
          attemptWasDurable.set(attemptStarted(ledger));
          crashed.countDown();
          throw new SimulatedProcessCrash();
        }
      };
    }

    private boolean awaitCrash() throws InterruptedException {
      return crashed.await(2, TimeUnit.SECONDS);
    }

    private boolean attemptWasDurable() {
      return attemptWasDurable.get();
    }
  }

  private static final class SimulatedProcessCrash extends Error {
    private SimulatedProcessCrash() {
      super("simulated crash before Telegram HTTP", null, false, false);
    }
  }
}
