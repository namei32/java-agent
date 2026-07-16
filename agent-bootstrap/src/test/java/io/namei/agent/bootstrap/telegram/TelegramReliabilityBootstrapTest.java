package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ChatResult;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.bootstrap.channel.ChannelHost;
import io.namei.agent.bootstrap.channel.ChannelReliabilityStatus;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityRuntime;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class TelegramReliabilityBootstrapTest {
  private static final String FAKE_TOKEN = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234";

  @TempDir Path temporaryDirectory;

  @Test
  void reliabilityDisabledCreatesNoRuntimeDirectoryDatabaseWorkerTokenReadOrNetwork() {
    Path workspace = temporaryDirectory.resolve("disabled-workspace");
    var secret = new CountingSecretSource(FAKE_TOKEN);
    var api = new BlockingApi();

    webRunner(workspace)
        .withBean(TelegramSecretSource.class, () -> secret)
        .withBean(TelegramBotApi.class, () -> api)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ChannelReliabilityRuntime.class);
              assertThat(context).doesNotHaveBean(TelegramReliableChannelAdapter.class);
              assertThat(context).doesNotHaveBean(TelegramBotToken.class);
              assertThat(context.getBean(ChannelHost.class).snapshots()).isEmpty();
              assertThat(secret.reads).hasValue(0);
              assertThat(api.pollCalls).hasValue(0);
              assertThat(workspace.resolve("channels")).doesNotExist();
            });

    assertThat(reliableWorkers()).isEmpty();
  }

  @Test
  void telegramDisabledDoesNotReadTheTokenEvenWhenSqliteReliabilityWasSelected() {
    Path workspace = temporaryDirectory.resolve("telegram-disabled-workspace");
    var secret = new CountingSecretSource(FAKE_TOKEN);
    var api = new BlockingApi();

    webRunner(workspace)
        .withBean(TelegramSecretSource.class, () -> secret)
        .withBean(TelegramBotApi.class, () -> api)
        .withPropertyValues("agent.channels.reliability.mode=SQLITE")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ChannelReliabilityRuntime.class);
              assertThat(context).doesNotHaveBean(TelegramReliableChannelAdapter.class);
              assertThat(context).doesNotHaveBean(TelegramBotToken.class);
              assertThat(secret.reads).hasValue(0);
              assertThat(api.pollCalls).hasValue(0);
              assertThat(workspace.resolve("channels")).doesNotExist();
            });
  }

  @Test
  void sqliteModeBuildsOnlyTheReliableAdapterAndInitializesLazilyAtHostStart()
      throws InterruptedException {
    Path workspace = temporaryDirectory.resolve("sqlite-workspace");
    var secret = new CountingSecretSource(FAKE_TOKEN);
    var api = new BlockingApi();
    var threads = new RecordingThreadStarter();

    webRunner(workspace)
        .withBean(TelegramSecretSource.class, () -> secret)
        .withBean(TelegramBotApi.class, () -> api)
        .withBean(ChannelThreadStarter.class, () -> threads)
        .withPropertyValues(
            "agent.channels.telegram.enabled=true",
            "agent.channels.telegram.allow-from=10001",
            "agent.channels.reliability.mode=SQLITE")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ChannelReliabilityRuntime.class);
              assertThat(context).hasSingleBean(TelegramReliableChannelAdapter.class);
              assertThat(context).doesNotHaveBean(TelegramChannelAdapter.class);
              assertThat(secret.reads).hasValue(1);
              assertThat(api.polled.await(2, TimeUnit.SECONDS)).isTrue();
              assertThat(workspace.resolve("channels/channel-ledger.db")).isRegularFile();
              assertThat(threads.names())
                  .containsOnlyOnce("channel-delivery-worker")
                  .containsOnlyOnce("telegram-reliable-poll-worker");
              assertThat(context.getBean(ChannelHost.class).snapshots())
                  .singleElement()
                  .satisfies(
                      snapshot -> {
                        assertThat(snapshot.state()).isEqualTo(ChannelState.RUNNING);
                        assertThat(snapshot.reliability().mode())
                            .isEqualTo(ChannelReliabilityStatus.Mode.SQLITE);
                        assertThat(snapshot.reliability().ledgerState())
                            .isEqualTo(ChannelReliabilityStatus.LedgerState.READY);
                        assertThat(snapshot.reliability().pendingDeliveries()).isZero();
                      });
            });

    assertThat(api.interruptions).hasValue(1);
    assertThat(threads.allStopped()).isTrue();
    assertThat(reliableWorkers()).isEmpty();
  }

  @Test
  void ledgerFailureIsIsolatedToTelegramAndNeverTouchesTheNetwork() throws Exception {
    Path workspace = temporaryDirectory.resolve("broken-workspace");
    Files.createDirectories(workspace);
    Files.writeString(workspace.resolve("channels"), "not-a-directory");
    var secret = new CountingSecretSource(FAKE_TOKEN);
    var api = new BlockingApi();

    webRunner(workspace)
        .withBean(TelegramSecretSource.class, () -> secret)
        .withBean(TelegramBotApi.class, () -> api)
        .withBean(HealthyServletComponent.class, HealthyServletComponent::new)
        .withPropertyValues(
            "agent.channels.telegram.enabled=true",
            "agent.channels.telegram.allow-from=10001",
            "agent.channels.reliability.mode=SQLITE")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(HealthyServletComponent.class);
              assertThat(context.getBean(ChannelHost.class).state())
                  .isEqualTo(ChannelHost.State.RUNNING);
              assertThat(context.getBean(ChannelHost.class).snapshots())
                  .singleElement()
                  .satisfies(
                      snapshot -> {
                        assertThat(snapshot.state()).isEqualTo(ChannelState.FAILED);
                        assertThat(snapshot.code()).isEqualTo("CHANNEL_LEDGER_UNAVAILABLE");
                        assertThat(snapshot.reliability().mode())
                            .isEqualTo(ChannelReliabilityStatus.Mode.SQLITE);
                        assertThat(snapshot.reliability().ledgerState())
                            .isEqualTo(ChannelReliabilityStatus.LedgerState.FAILED);
                        assertThat(snapshot.reliability().lastStableErrorCode())
                            .isEqualTo("CHANNEL_LEDGER_UNAVAILABLE");
                      });
              assertThat(api.pollCalls).hasValue(0);
            });

    assertThat(reliableWorkers()).isEmpty();
  }

  @Test
  void templatesKeepReliabilityExplicitlyDisabledWithOnlyNonSecretBudgets() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
    String environment = Files.readString(Path.of("../.env.example"));

    assertThat(yaml)
        .contains("mode: ${AGENT_CHANNEL_RELIABILITY_MODE:DISABLED}")
        .contains("recovery-batch-size: ${AGENT_CHANNEL_RELIABILITY_RECOVERY_BATCH_SIZE:100}")
        .contains("max-delivery-records: ${AGENT_CHANNEL_RELIABILITY_MAX_DELIVERY_RECORDS:10000}")
        .doesNotContain("channel-ledger.db");
    assertThat(environment)
        .contains("AGENT_CHANNEL_RELIABILITY_MODE=DISABLED")
        .contains("AGENT_CHANNEL_RELIABILITY_RETENTION=30d")
        .doesNotContain("channel-ledger.db");
  }

  private static WebApplicationContextRunner webRunner(Path workspace) {
    return new WebApplicationContextRunner()
        .withUserConfiguration(TelegramChannelConfiguration.class)
        .withPropertyValues(
            "agent.workspace=" + workspace,
            "agent.channels.telegram.enabled=false",
            "agent.channels.reliability.mode=DISABLED")
        .withBean(
            MessageTurnService.class,
            () ->
                new MessageTurnService(
                    command ->
                        new ChatResult(
                            command.sessionId(),
                            new ChatMessage(MessageRole.ASSISTANT, "not-used"))));
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

  private static final class CountingSecretSource implements TelegramSecretSource {
    private final String token;
    private final AtomicInteger reads = new AtomicInteger();

    private CountingSecretSource(String token) {
      this.token = token;
    }

    @Override
    public String readToken() {
      reads.incrementAndGet();
      return token;
    }
  }

  private static final class BlockingApi implements TelegramBotApi {
    private final CountDownLatch polled = new CountDownLatch(1);
    private final AtomicInteger pollCalls = new AtomicInteger();
    private final AtomicInteger interruptions = new AtomicInteger();

    @Override
    public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
      pollCalls.incrementAndGet();
      polled.countDown();
      try {
        new CountDownLatch(1).await();
        throw new AssertionError("阻塞 Poll 不应完成");
      } catch (InterruptedException interrupted) {
        interruptions.incrementAndGet();
        Thread.currentThread().interrupt();
        throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
      }
    }

    @Override
    public TelegramSendReceipt sendMessage(long chatId, String text) {
      throw new AssertionError("Bootstrap 测试不应发送");
    }
  }

  private static final class HealthyServletComponent {}

  private static final class RecordingThreadStarter implements ChannelThreadStarter {
    private final List<Thread> threads = new CopyOnWriteArrayList<>();

    @Override
    public Thread start(String name, Runnable task) {
      Thread thread = Thread.ofVirtual().name(name).start(task);
      threads.add(thread);
      return thread;
    }

    private List<String> names() {
      return threads.stream().map(Thread::getName).toList();
    }

    private boolean allStopped() {
      return threads.stream().noneMatch(Thread::isAlive);
    }
  }
}
