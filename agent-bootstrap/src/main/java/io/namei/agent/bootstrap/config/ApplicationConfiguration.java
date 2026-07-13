package io.namei.agent.bootstrap.config;

import io.namei.agent.adapter.springai.SpringAiAdapterConfiguration;
import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import io.namei.agent.adapter.sqlite.SqliteSchemaInitializer;
import io.namei.agent.application.ChatService;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.KeyedSessionExecutionGate;
import io.namei.agent.application.SessionExecutionGate;
import io.namei.agent.bootstrap.health.SqliteHealthIndicator;
import io.namei.agent.bootstrap.observability.ObservedChatModelPort;
import io.namei.agent.bootstrap.observability.ObservedSessionRepository;
import io.namei.agent.bootstrap.observability.SafeChatUseCase;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
@Import(SpringAiAdapterConfiguration.class)
public class ApplicationConfiguration {
  @Bean
  InitializingBean providerConfigurationGuard(Environment environment) {
    return () -> new ProviderConfigurationGuard(environment).validate();
  }

  @Bean
  SqliteSchemaInitializer sqliteSchema(AgentProperties properties) {
    try {
      Files.createDirectories(properties.workspace());
    } catch (IOException exception) {
      throw new IllegalStateException("无法创建工作区", exception);
    }
    var schema = new SqliteSchemaInitializer(properties.workspace().resolve("sessions.db"), 5_000);
    schema.initialize();
    return schema;
  }

  @Bean
  JdbcSessionRepository jdbcSessionRepository(SqliteSchemaInitializer schema) {
    return new JdbcSessionRepository(schema);
  }

  @Bean
  @Primary
  SessionRepository sessionRepository(JdbcSessionRepository repository) {
    return new ObservedSessionRepository(repository);
  }

  @Bean
  SessionExecutionGate sessionExecutionGate(AgentProperties properties) {
    return new KeyedSessionExecutionGate(properties.model().timeout());
  }

  @Bean
  ChatUseCase chatUseCase(
      SessionRepository sessions,
      ChatModelPort model,
      SessionExecutionGate gate,
      AgentProperties properties,
      @Value("${spring.ai.openai.chat.model}") String modelName,
      @Value("classpath:/prompts/system.md") Resource systemPrompt)
      throws IOException {
    String prompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
    var service =
        new ChatService(
            sessions,
            new ObservedChatModelPort(model, modelName),
            new ConversationHistorySelector(),
            new HistoryLimits(
                properties.history().maxMessages(), properties.history().maxCharacters()),
            gate,
            prompt,
            Clock.systemUTC());
    return new SafeChatUseCase(service, Clock.systemUTC());
  }

  @Bean
  SqliteHealthIndicator sqliteHealthIndicator(JdbcSessionRepository repository) {
    return new SqliteHealthIndicator(repository);
  }
}
