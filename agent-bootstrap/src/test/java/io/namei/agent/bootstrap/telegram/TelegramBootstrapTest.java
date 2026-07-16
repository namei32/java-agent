package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ChatResult;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.bootstrap.channel.ChannelHost;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.bootstrap.config.ConfigurationCheckCommand;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class TelegramBootstrapTest {
  private static final String FAKE_TOKEN = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234";

  @TempDir Path temporaryDirectory;

  @Test
  void defaultServletContextStartsAnEmptyHostWithoutSecretApiAdapterNetworkOrWorker() {
    var host = new AtomicReference<ChannelHost>();
    assertThat(telegramWorkers()).isEmpty();

    webRunner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ChannelHost.class);
              host.set(context.getBean(ChannelHost.class));
              assertThat(host.get().state()).isEqualTo(ChannelHost.State.RUNNING);
              assertThat(host.get().snapshots()).isEmpty();
              assertThat(context.getBean(TelegramProperties.class).enabled()).isFalse();
              assertThat(context).doesNotHaveBean(TelegramSecretSource.class);
              assertThat(context).doesNotHaveBean(TelegramBotToken.class);
              assertThat(context).doesNotHaveBean(TelegramBotApi.class);
              assertThat(context).doesNotHaveBean(TelegramChannelAdapter.class);
              assertThat(telegramWorkers()).isEmpty();
            });

    assertThat(host.get().state()).isEqualTo(ChannelHost.State.STOPPED);
    assertThat(telegramWorkers()).isEmpty();
  }

  @Test
  void nonWebContextIgnoresAnAccidentalEnabledFlagWithoutBindingOrReadingTelegram() {
    new ApplicationContextRunner()
        .withUserConfiguration(TelegramChannelConfiguration.class)
        .withPropertyValues("agent.channels.telegram.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ChannelHost.class);
              assertThat(context).doesNotHaveBean(TelegramProperties.class);
              assertThat(context).doesNotHaveBean(TelegramSecretSource.class);
              assertThat(context).doesNotHaveBean(TelegramBotApi.class);
              assertThat(context).doesNotHaveBean(TelegramChannelAdapter.class);
              assertThat(telegramWorkers()).isEmpty();
            });
  }

  @Test
  void enabledModeFailsClosedWhenTheAllowlistIsEmptyBeforeStartingAWorker() {
    var source = new CountingSecretSource(FAKE_TOKEN);
    webRunner()
        .withBean(TelegramSecretSource.class, () -> source)
        .withPropertyValues("agent.channels.telegram.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("agent.channels.telegram.allow-from 启用时不能为空");
              assertThat(source.reads).hasValue(0);
              assertThat(telegramWorkers()).isEmpty();
            });
  }

  @Test
  void enabledModeFailsClosedWhenTheTokenIsMissingOrInvalidWithoutLeakingIt() {
    var missing = new CountingSecretSource(null);
    enabledRunner(missing, new BlockingApi())
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("Telegram Bot Token 格式无效");
              assertThat(missing.reads).hasValue(1);
            });

    String invalidSecret = "invalid-token-secret";
    var invalid = new CountingSecretSource(invalidSecret);
    enabledRunner(invalid, new BlockingApi())
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("Telegram Bot Token 格式无效");
              assertThat(stackTrace(context.getStartupFailure())).doesNotContain(invalidSecret);
              assertThat(invalid.reads).hasValue(1);
            });
  }

  @Test
  void enabledModeRejectsAnInvalidPollDeadlineBeforeStartingNetwork() {
    var source = new CountingSecretSource(FAKE_TOKEN);
    var api = new BlockingApi();
    enabledRunner(source, api)
        .withPropertyValues(
            "agent.channels.telegram.long-poll-timeout=2s",
            "agent.channels.telegram.poll-request-timeout=1s")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("poll-request-timeout 必须大于 long-poll-timeout");
              assertThat(source.reads).hasValue(0);
              assertThat(api.pollCalls).hasValue(0);
              assertThat(telegramWorkers()).isEmpty();
            });
  }

  @Test
  void enabledServletContextUsesTheInjectedFakeAndStopsItOnContextClose()
      throws InterruptedException {
    var source = new CountingSecretSource(FAKE_TOKEN);
    var api = new BlockingApi();
    var adapter = new AtomicReference<TelegramChannelAdapter>();

    enabledRunner(source, api)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ChannelHost.class);
              assertThat(context).hasSingleBean(TelegramChannelAdapter.class);
              assertThat(context.getBean(TelegramBotApi.class)).isSameAs(api);
              assertThat(source.reads).hasValue(1);
              adapter.set(context.getBean(TelegramChannelAdapter.class));
              assertThat(api.polled.await(2, TimeUnit.SECONDS)).isTrue();
              assertThat(context.getBean(ChannelHost.class).snapshots())
                  .singleElement()
                  .satisfies(
                      snapshot -> {
                        assertThat(snapshot.name()).isEqualTo("telegram");
                        assertThat(snapshot.state()).isEqualTo(ChannelState.RUNNING);
                      });
            });

    assertThat(api.interruptions).hasValue(1);
    assertThat(adapter.get().snapshot().state()).isEqualTo(ChannelState.STOPPED);
    assertThat(adapter.get().snapshot().activeTurns()).isZero();
    assertThat(telegramWorkers()).isEmpty();
  }

  @Test
  void templatesKeepTelegramDisabledAndNeverResolveTheTokenThroughYaml() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
    String environment = Files.readString(Path.of("../.env.example"));

    assertThat(yaml)
        .contains("enabled: ${AGENT_TELEGRAM_ENABLED:false}")
        .contains("allow-from: ${AGENT_TELEGRAM_ALLOW_FROM:}")
        .contains("max-concurrent-turns: ${AGENT_TELEGRAM_MAX_CONCURRENT_TURNS:8}")
        .contains("buffer-capacity: ${AGENT_TELEGRAM_BUFFER_CAPACITY:32}")
        .contains("long-poll-timeout: ${AGENT_TELEGRAM_LONG_POLL_TIMEOUT:20s}")
        .contains("poll-request-timeout: ${AGENT_TELEGRAM_POLL_REQUEST_TIMEOUT:25s}")
        .contains("shutdown-timeout: ${AGENT_TELEGRAM_SHUTDOWN_TIMEOUT:5s}")
        .doesNotContain("AGENT_TELEGRAM_BOT_TOKEN");
    assertThat(environment)
        .contains("AGENT_TELEGRAM_ENABLED=false")
        .contains("AGENT_TELEGRAM_ALLOW_FROM=")
        .contains("AGENT_TELEGRAM_BOT_TOKEN=")
        .contains("AGENT_TELEGRAM_LONG_POLL_TIMEOUT=20s");
  }

  @Test
  void configurationCheckFiltersTheTelegramTokenBeforeCopyingEnvironmentEntries() {
    var output = new ByteArrayOutputStream();

    int exitCode =
        ConfigurationCheckCommand.run(
            new String[] {"--agent.config-check"},
            new GuardedConfigurationEnvironment(),
            temporaryDirectory,
            new PrintStream(output, true, StandardCharsets.UTF_8));

    assertThat(exitCode).isZero();
    assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(FAKE_TOKEN);
    assertThat(telegramWorkers()).isEmpty();
  }

  private static WebApplicationContextRunner webRunner() {
    return new WebApplicationContextRunner()
        .withUserConfiguration(TelegramChannelConfiguration.class)
        .withBean(
            MessageTurnService.class,
            () ->
                new MessageTurnService(
                    command ->
                        new ChatResult(
                            command.sessionId(), new ChatMessage(MessageRole.ASSISTANT, "不应执行"))));
  }

  private static WebApplicationContextRunner enabledRunner(
      TelegramSecretSource source, TelegramBotApi api) {
    return webRunner()
        .withBean(TelegramSecretSource.class, () -> source)
        .withBean(TelegramBotApi.class, () -> api)
        .withPropertyValues(
            "agent.channels.telegram.enabled=true", "agent.channels.telegram.allow-from=10001");
  }

  private static List<String> telegramWorkers() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .map(Thread::getName)
        .filter(name -> name.startsWith("telegram-"))
        .sorted()
        .toList();
  }

  private static String stackTrace(Throwable failure) {
    var rendered = new StringWriter();
    failure.printStackTrace(new PrintWriter(rendered));
    return rendered.toString();
  }

  private static final class CountingSecretSource implements TelegramSecretSource {
    private final String value;
    private final AtomicInteger reads = new AtomicInteger();

    private CountingSecretSource(String value) {
      this.value = value;
    }

    @Override
    public String readToken() {
      reads.incrementAndGet();
      return value;
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
        throw new AssertionError("阻塞 Poll 不应自行完成");
      } catch (InterruptedException interrupted) {
        interruptions.incrementAndGet();
        Thread.currentThread().interrupt();
        throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
      }
    }

    @Override
    public void sendMessage(long chatId, String text) {
      throw new AssertionError("Bootstrap 测试不应发送消息");
    }
  }

  private static final class GuardedConfigurationEnvironment extends AbstractMap<String, String> {
    @Override
    public Set<Entry<String, String>> entrySet() {
      return Set.of(
          Map.entry("OPENAI_BASE_URL", "http://127.0.0.1:1/v1"),
          Map.entry("OPENAI_API_KEY", "test-key"),
          Map.entry("OPENAI_MODEL", "test-model"),
          new SimpleEntry<>("AGENT_TELEGRAM_BOT_TOKEN", FAKE_TOKEN) {
            @Override
            public String getValue() {
              throw new AssertionError("配置检查不得读取 Telegram Token");
            }
          });
    }
  }
}
