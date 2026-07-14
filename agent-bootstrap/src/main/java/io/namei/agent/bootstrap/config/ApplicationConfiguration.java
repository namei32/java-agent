package io.namei.agent.bootstrap.config;

import io.namei.agent.adapter.springai.SpringAiAdapterConfiguration;
import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import io.namei.agent.adapter.sqlite.SqliteSchemaInitializer;
import io.namei.agent.adapter.workspace.MarkdownMemoryProfileAdapter;
import io.namei.agent.application.ApprovalPort;
import io.namei.agent.application.ChatService;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.KeyedSessionExecutionGate;
import io.namei.agent.application.MemoryContextService;
import io.namei.agent.application.SecureIdGenerator;
import io.namei.agent.application.SessionExecutionGate;
import io.namei.agent.application.SideEffectLedger;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.application.ToolRuntimeSettings;
import io.namei.agent.bootstrap.health.SqliteHealthIndicator;
import io.namei.agent.bootstrap.observability.ObservedChatModelPort;
import io.namei.agent.bootstrap.observability.ObservedSessionRepository;
import io.namei.agent.bootstrap.observability.SafeChatUseCase;
import io.namei.agent.bootstrap.tool.CurrentTimeTool;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.MemoryProfilePort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
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
  TurnLifecycleObserver turnLifecycleObserver() {
    return TurnLifecycleObserver.noop();
  }

  @Bean
  ApprovalPort approvalPort() {
    return new DenyAllApprovalPort(Clock.systemUTC());
  }

  @Bean
  MemoryProfilePort memoryProfilePort(AgentProperties properties) {
    return switch (properties.memory().mode()) {
      case DISABLED -> MemoryProfilePort.empty();
      case READ_ONLY ->
          new MarkdownMemoryProfileAdapter(
              properties.workspace(), properties.memory().maxFileBytes());
    };
  }

  @Bean
  MemoryRetrievalPort memoryRetrievalPort() {
    return MemoryRetrievalPort.disabled();
  }

  @Bean
  MemoryContextService memoryContextService(
      MemoryProfilePort profiles, MemoryRetrievalPort retrieval, AgentProperties properties) {
    return new MemoryContextService(
        profiles,
        retrieval,
        properties.memory().maxContextCharacters(),
        properties.memory().maxRetrievedCharacters());
  }

  @Bean
  ChatUseCase chatUseCase(
      SessionRepository sessions,
      ChatModelPort model,
      SessionExecutionGate gate,
      TurnLifecycleObserver lifecycleObserver,
      ApprovalPort approvalPort,
      MemoryContextService memoryContext,
      AgentProperties properties,
      @Value("${spring.ai.openai.chat.model}") String modelName,
      @Value("${agent.compatibility.system-prompt-base64:}") String compatibilityPrompt,
      @Value("classpath:/prompts/system.md") Resource systemPrompt)
      throws IOException {
    String prompt = systemPrompt(compatibilityPrompt, systemPrompt);
    List<Tool> tools =
        properties.tools().mode() != ToolRuntimeMode.DISABLED
            ? List.of(new CurrentTimeTool(Clock.systemUTC()))
            : List.of();
    var toolSettings =
        new ToolRuntimeSettings(
            properties.tools().mode(),
            properties.tools().maxCallsPerResponse(),
            properties.tools().maxCallsPerTurn(),
            properties.tools().timeout(),
            properties.tools().maxConcurrentCalls(),
            properties.tools().maxResultCharacters());
    var service =
        new ChatService(
            sessions,
            new ObservedChatModelPort(model, modelName),
            new ConversationHistorySelector(),
            new HistoryLimits(
                properties.history().maxMessages(), properties.history().maxCharacters()),
            gate,
            prompt,
            Clock.systemUTC(),
            tools,
            properties.toolLoop().maxIterations(),
            lifecycleObserver,
            toolSettings,
            approvalPort,
            SideEffectLedger.unavailable(),
            new SecureIdGenerator(),
            properties.tools().approvalTimeout(),
            memoryContext);
    return new SafeChatUseCase(service, Clock.systemUTC());
  }

  String systemPrompt(String compatibilityPrompt, Resource fallback) throws IOException {
    if (compatibilityPrompt == null || compatibilityPrompt.isEmpty()) {
      return fallback.getContentAsString(StandardCharsets.UTF_8).strip();
    }
    return new String(Base64.getDecoder().decode(compatibilityPrompt), StandardCharsets.UTF_8);
  }

  @Bean
  SqliteHealthIndicator sqliteHealthIndicator(JdbcSessionRepository repository) {
    return new SqliteHealthIndicator(repository);
  }
}
