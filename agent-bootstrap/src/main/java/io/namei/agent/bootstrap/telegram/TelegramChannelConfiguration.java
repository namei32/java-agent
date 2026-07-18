package io.namei.agent.bootstrap.telegram;

import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.control.ActiveTurnObserver;
import io.namei.agent.bootstrap.channel.ChannelAdapter;
import io.namei.agent.bootstrap.channel.ChannelHost;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityProperties;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityRuntime;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({TelegramProperties.class, ChannelReliabilityProperties.class})
public class TelegramChannelConfiguration {
  private static final String ENABLED_PREFIX = "agent.channels.telegram";
  private static final String TOKEN_ENVIRONMENT_VARIABLE = "AGENT_TELEGRAM_BOT_TOKEN";

  @Bean(initMethod = "start", destroyMethod = "close")
  ChannelHost channelHost(ObjectProvider<ChannelAdapter> adapters) {
    List<ChannelAdapter> configured = adapters.orderedStream().toList();
    return new ChannelHost(configured);
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnMissingBean(TelegramSecretSource.class)
  TelegramSecretSource telegramSecretSource() {
    return () -> System.getenv(TOKEN_ENVIRONMENT_VARIABLE);
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  TelegramBotToken telegramBotToken(TelegramProperties properties, TelegramSecretSource source) {
    if (!properties.enabled()) {
      throw new IllegalStateException("Telegram 配置状态不一致");
    }
    return new TelegramBotToken(source.readToken());
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnProperty(
      prefix = "agent.channels.reliability",
      name = "mode",
      havingValue = "SQLITE")
  TelegramChannelInstance telegramChannelInstance(TelegramBotToken token) {
    return TelegramChannelInstance.from(token);
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnProperty(
      prefix = "agent.channels.reliability",
      name = "mode",
      havingValue = "SQLITE")
  ChannelReliabilityRuntime channelReliabilityRuntime(
      @Value("${agent.workspace:./workspace}") String workspace,
      ChannelReliabilityProperties properties) {
    return new ChannelReliabilityRuntime(Path.of(workspace), properties, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnMissingBean(TelegramIdGenerator.class)
  TelegramIdGenerator telegramIdGenerator() {
    return new SecureTelegramIdGenerator();
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  TelegramUpdateMapper telegramUpdateMapper(
      TelegramProperties properties, TelegramIdGenerator ids) {
    return new TelegramUpdateMapper(properties.allowedUserIds(), ids);
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnMissingBean(TelegramBotApi.class)
  TelegramBotApi telegramBotApi(TelegramBotToken token, TelegramProperties properties) {
    return new JdkTelegramBotApi(token, properties);
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnMissingBean(ChannelThreadStarter.class)
  ChannelThreadStarter telegramThreadStarter() {
    return ChannelThreadStarter.virtualThreads();
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnMissingBean(ChannelSleeper.class)
  ChannelSleeper telegramSleeper() {
    return ChannelSleeper.system();
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnProperty(
      prefix = "agent.channels.reliability",
      name = "mode",
      havingValue = "DISABLED",
      matchIfMissing = true)
  TelegramChannelAdapter telegramChannelAdapter(
      TelegramBotApi api,
      TelegramUpdateMapper mapper,
      MessageTurnService turns,
      TelegramProperties properties,
      ChannelThreadStarter threadStarter,
      ChannelSleeper sleeper,
      ObjectProvider<ActiveTurnObserver> activeTurnObservers) {
    return new TelegramChannelAdapter(
        api,
        mapper,
        turns,
        properties,
        threadStarter,
        sleeper,
        Clock.systemUTC(),
        activeTurnObservers.getIfAvailable(ActiveTurnObserver::disabled));
  }

  @Bean
  @ConditionalOnProperty(prefix = ENABLED_PREFIX, name = "enabled", havingValue = "true")
  @ConditionalOnProperty(
      prefix = "agent.channels.reliability",
      name = "mode",
      havingValue = "SQLITE")
  TelegramReliableChannelAdapter telegramReliableChannelAdapter(
      TelegramBotApi api,
      TelegramUpdateMapper mapper,
      MessageTurnService turns,
      TelegramProperties properties,
      TelegramChannelInstance instance,
      ChannelReliabilityRuntime runtime,
      ChannelThreadStarter threadStarter,
      ChannelSleeper sleeper,
      ObjectProvider<ActiveTurnObserver> activeTurnObservers) {
    return new TelegramReliableChannelAdapter(
        api,
        mapper,
        turns,
        properties,
        instance,
        runtime,
        threadStarter,
        sleeper,
        activeTurnObservers.getIfAvailable(ActiveTurnObserver::disabled));
  }
}
