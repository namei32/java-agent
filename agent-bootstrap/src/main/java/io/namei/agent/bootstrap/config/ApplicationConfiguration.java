package io.namei.agent.bootstrap.config;

import io.namei.agent.adapter.mcp.McpConfigLoader;
import io.namei.agent.adapter.mcp.McpMode;
import io.namei.agent.adapter.mcp.McpRuntime;
import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.adapter.springai.SpringAiAdapterConfiguration;
import io.namei.agent.adapter.springai.SpringAiEmbeddingAdapter;
import io.namei.agent.adapter.sqlite.Float32VectorCodec;
import io.namei.agent.adapter.sqlite.JavaMemorySchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcConversationEvidenceRepository;
import io.namei.agent.adapter.sqlite.JdbcJavaMemoryStore;
import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import io.namei.agent.adapter.sqlite.SqliteSchemaInitializer;
import io.namei.agent.adapter.workspace.MarkdownMemoryProfileAdapter;
import io.namei.agent.adapter.workspace.MarkdownSkillCatalogAdapter;
import io.namei.agent.adapter.workspace.SkillCatalogLimits;
import io.namei.agent.adapter.workspace.SkillRequirementChecker;
import io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset;
import io.namei.agent.adapter.workspace.WorkspaceToolMode;
import io.namei.agent.application.AkashicCorePromptRenderer;
import io.namei.agent.application.ApprovalPort;
import io.namei.agent.application.ChatService;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.ConversationEvidenceContextFactory;
import io.namei.agent.application.ConversationEvidenceToolset;
import io.namei.agent.application.KeyedSessionExecutionGate;
import io.namei.agent.application.MemoryContextService;
import io.namei.agent.application.MemoryDeleteService;
import io.namei.agent.application.MemoryQueryService;
import io.namei.agent.application.MemoryRecallContextFactory;
import io.namei.agent.application.MemoryRecallToolset;
import io.namei.agent.application.MemoryWriteService;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.ModelStreamingSettings;
import io.namei.agent.application.PromptRuntimeSettings;
import io.namei.agent.application.PromptTurnContextFactory;
import io.namei.agent.application.ReadOnlyMemoryRecallService;
import io.namei.agent.application.SecureIdGenerator;
import io.namei.agent.application.SemanticMemoryRetrievalAdapter;
import io.namei.agent.application.SemanticMemoryRetrievalSettings;
import io.namei.agent.application.SessionExecutionGate;
import io.namei.agent.application.SideEffectLedger;
import io.namei.agent.application.SkillPromptService;
import io.namei.agent.application.ToolCatalog;
import io.namei.agent.application.ToolCatalogEntry;
import io.namei.agent.application.ToolCatalogSource;
import io.namei.agent.application.ToolCatalogVisibility;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.application.ToolRuntimeSettings;
import io.namei.agent.bootstrap.cli.CliIdGenerator;
import io.namei.agent.bootstrap.cli.CliInput;
import io.namei.agent.bootstrap.cli.CliOutput;
import io.namei.agent.bootstrap.cli.CliProperties;
import io.namei.agent.bootstrap.cli.CliThreadStarter;
import io.namei.agent.bootstrap.cli.LocalCliRunner;
import io.namei.agent.bootstrap.cli.SecureCliIdGenerator;
import io.namei.agent.bootstrap.cli.Utf8CliInput;
import io.namei.agent.bootstrap.cli.Utf8CliOutput;
import io.namei.agent.bootstrap.health.SqliteHealthIndicator;
import io.namei.agent.bootstrap.http.MemoryManagementApi;
import io.namei.agent.bootstrap.observability.ObservedChatModelPort;
import io.namei.agent.bootstrap.observability.ObservedSessionRepository;
import io.namei.agent.bootstrap.observability.SafeChatUseCase;
import io.namei.agent.bootstrap.plugin.JavaServicePluginDiscovery;
import io.namei.agent.bootstrap.plugin.JdkExternalStdioPluginTransport;
import io.namei.agent.bootstrap.plugin.PluginProperties;
import io.namei.agent.bootstrap.plugin.PluginRuntime;
import io.namei.agent.bootstrap.proactive.ProactiveProperties;
import io.namei.agent.bootstrap.proactive.ProactiveRuntime;
import io.namei.agent.bootstrap.tool.CurrentTimeTool;
import io.namei.agent.bootstrap.tool.ReadSkillTool;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.ConversationEvidencePort;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryProfilePort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import io.namei.agent.kernel.skill.SkillCatalogMode;
import io.namei.agent.kernel.skill.SkillCatalogPort;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  AgentProperties.class,
  PromptProperties.class,
  SkillProperties.class,
  WorkspaceToolProperties.class,
  McpProperties.class,
  McpAssetProperties.class,
  ConversationEvidenceProperties.class,
  MemoryRecallProperties.class,
  CliProperties.class,
  PluginProperties.class,
  ProactiveProperties.class
})
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
  ConversationEvidencePort conversationEvidencePort(
      AgentProperties agentProperties,
      ConversationEvidenceProperties properties,
      SqliteSchemaInitializer schema) {
    if (!conversationEvidenceEnabled(agentProperties, properties)) {
      return ConversationEvidencePort.disabled();
    }
    if (ConversationEvidenceToolset.MAX_PROJECTED_CODE_POINTS
        > agentProperties.tools().maxResultCharacters()) {
      throw new IllegalStateException(
          "agent.conversation-evidence 单项预算不能大于 agent.tools.max-result-characters");
    }
    return new JdbcConversationEvidenceRepository(schema);
  }

  @Bean
  ConversationEvidenceContextFactory conversationEvidenceContextFactory(
      AgentProperties agentProperties,
      ConversationEvidenceProperties properties,
      ConversationEvidencePort port) {
    return conversationEvidenceEnabled(agentProperties, properties)
        ? ConversationEvidenceContextFactory.enabled(port)
        : ConversationEvidenceContextFactory.disabled();
  }

  @Bean
  ConversationEvidenceToolset conversationEvidenceToolset(
      AgentProperties agentProperties, ConversationEvidenceProperties properties) {
    return conversationEvidenceEnabled(agentProperties, properties)
        ? ConversationEvidenceToolset.enabled()
        : ConversationEvidenceToolset.disabled();
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

  TurnLifecycleObserver turnLifecycleObserver() {
    return TurnLifecycleObserver.noop();
  }

  @Bean(destroyMethod = "close")
  PluginRuntime pluginRuntime(AgentProperties agentProperties, PluginProperties pluginProperties) {
    return PluginRuntime.start(
        pluginProperties,
        agentProperties.tools().mode(),
        JavaServicePluginDiscovery.classpath(),
        JdkExternalStdioPluginTransport::start);
  }

  @Bean(destroyMethod = "close")
  ProactiveRuntime proactiveRuntime(
      AgentProperties agentProperties,
      ProactiveProperties proactiveProperties,
      PluginRuntime plugins) {
    return ProactiveRuntime.start(proactiveProperties, agentProperties.workspace(), plugins);
  }

  @Bean
  TurnLifecycleObserver turnLifecycleObserver(PluginRuntime plugins) {
    return plugins.lifecycleObserver();
  }

  @Bean
  ApprovalPort approvalPort() {
    return new DenyAllApprovalPort(Clock.systemUTC());
  }

  @Bean
  JavaNativeMemoryAccessGuard javaNativeMemoryAccessGuard(
      AgentProperties properties, Environment environment) {
    var guard = new JavaNativeMemoryAccessGuard(properties, environment);
    guard.validate();
    return guard;
  }

  @Bean
  @ConditionalOnProperty(prefix = "agent.memory", name = "mode", havingValue = "JAVA_NATIVE")
  JavaMemorySchemaInitializer javaMemorySchema(
      AgentProperties properties, JavaNativeMemoryAccessGuard accessGuard) {
    Objects.requireNonNull(accessGuard, "accessGuard");
    var schema =
        new JavaMemorySchemaInitializer(
            properties.workspace().resolve("memory").resolve("agent-memory.db"), 5_000);
    schema.initialize();
    return schema;
  }

  @Bean
  @ConditionalOnProperty(prefix = "agent.memory", name = "mode", havingValue = "JAVA_NATIVE")
  JdbcJavaMemoryStore javaMemoryStore(JavaMemorySchemaInitializer schema) {
    return new JdbcJavaMemoryStore(schema, new Float32VectorCodec());
  }

  @Bean
  @ConditionalOnProperty(prefix = "agent.memory", name = "mode", havingValue = "JAVA_NATIVE")
  EmbeddingPort javaMemoryEmbeddingPort(EmbeddingModel embeddingModel, AgentProperties properties) {
    AgentProperties.Embedding embedding = properties.memory().embedding();
    var options = OpenAiEmbeddingOptions.builder();
    options.model(embedding.model());
    options.dimensions(embedding.dimensions());
    return new SpringAiEmbeddingAdapter(
        embeddingModel, options.build(), embedding.maxTextCodePoints());
  }

  @Bean
  MemoryProfilePort memoryProfilePort(AgentProperties properties) {
    return switch (properties.memory().mode()) {
      case DISABLED -> MemoryProfilePort.empty();
      case READ_ONLY ->
          new MarkdownMemoryProfileAdapter(
              properties.workspace(), properties.memory().maxFileBytes());
      case JAVA_NATIVE -> MemoryProfilePort.empty();
    };
  }

  @Bean
  MemoryRetrievalPort memoryRetrievalPort(
      AgentProperties properties,
      ObjectProvider<JdbcJavaMemoryStore> stores,
      ObjectProvider<EmbeddingPort> embeddings) {
    if (properties.memory().mode() != io.namei.agent.kernel.memory.MemoryRuntimeMode.JAVA_NATIVE) {
      return MemoryRetrievalPort.disabled();
    }
    return new SemanticMemoryRetrievalAdapter(
        required(stores), required(embeddings), semanticMemoryRetrievalSettings(properties));
  }

  @Bean
  MemoryRecallContextFactory memoryRecallContextFactory(
      AgentProperties properties,
      MemoryRecallProperties recallProperties,
      ObjectProvider<JdbcJavaMemoryStore> stores,
      ObjectProvider<EmbeddingPort> embeddings) {
    if (!memoryRecallEnabled(properties, recallProperties)) {
      return MemoryRecallContextFactory.disabled();
    }
    return MemoryRecallContextFactory.enabled(
        new ReadOnlyMemoryRecallService(
            required(stores),
            required(embeddings),
            semanticMemoryRetrievalSettings(properties),
            Clock.systemUTC()));
  }

  @Bean
  MemoryRecallToolset memoryRecallToolset(
      AgentProperties properties, MemoryRecallProperties recallProperties) {
    if (!memoryRecallEnabled(properties, recallProperties)) {
      return MemoryRecallToolset.disabled();
    }
    if (MemoryRecallToolset.MAX_PROJECTED_CODE_POINTS > properties.tools().maxResultCharacters()) {
      throw new IllegalStateException(
          "agent.memory-recall 单项预算不能大于 agent.tools.max-result-characters");
    }
    return MemoryRecallToolset.enabled();
  }

  @Bean
  MemoryManagementApi memoryManagementApi(
      AgentProperties properties,
      ObjectProvider<JdbcJavaMemoryStore> stores,
      ObjectProvider<EmbeddingPort> embeddings) {
    if (properties.memory().mode() != io.namei.agent.kernel.memory.MemoryRuntimeMode.JAVA_NATIVE) {
      return MemoryManagementApi.unavailable();
    }
    JdbcJavaMemoryStore store = required(stores);
    EmbeddingPort embedding = required(embeddings);
    Clock clock = Clock.systemUTC();
    return MemoryManagementApi.enabled(
        new MemoryWriteService(embedding, store, new SecureIdGenerator(), clock),
        new MemoryQueryService(store),
        new MemoryDeleteService(store, clock));
  }

  MemoryContextService memoryContextService(
      MemoryProfilePort profiles,
      MemoryRetrievalPort retrieval,
      AgentProperties properties,
      PromptProperties promptProperties) {
    return memoryContextService(
        profiles, retrieval, properties, promptProperties, SkillPromptService.disabled());
  }

  @Bean
  MemoryContextService memoryContextService(
      MemoryProfilePort profiles,
      MemoryRetrievalPort retrieval,
      AgentProperties properties,
      PromptProperties promptProperties,
      SkillPromptService skillPrompts) {
    return new MemoryContextService(
        profiles,
        retrieval,
        properties.memory().maxContextCharacters(),
        properties.memory().maxRetrievedCharacters(),
        new PromptRuntimeSettings(
            promptProperties.mode(), promptProperties.budget(), promptProperties.zoneId()),
        AkashicCorePromptRenderer.fromClasspath(),
        skillPrompts);
  }

  @Bean
  SkillCatalogPort skillCatalogPort(AgentProperties agentProperties, SkillProperties properties) {
    if (properties.mode() == SkillCatalogMode.DISABLED) {
      return SkillCatalogPort.disabled();
    }
    return new MarkdownSkillCatalogAdapter(
        properties.builtinRoot().orElse(null),
        agentProperties.workspace().resolve("skills"),
        SkillRequirementChecker.system(),
        new SkillCatalogLimits(properties.maxSkills(), properties.maxFileBytes()));
  }

  @Bean
  SkillPromptService skillPromptService(SkillCatalogPort catalog, SkillProperties properties) {
    return new SkillPromptService(
        catalog, properties.maxCatalogCodePoints(), properties.maxActiveCodePoints());
  }

  @Bean
  WorkspaceReadOnlyToolset workspaceReadOnlyToolset(
      AgentProperties agentProperties, WorkspaceToolProperties properties) {
    if (properties.mode() == WorkspaceToolMode.DISABLED) {
      return WorkspaceReadOnlyToolset.disabled();
    }
    if (agentProperties.tools().mode() != ToolRuntimeMode.READ_ONLY) {
      throw new IllegalStateException("只读 Workspace Tool 要求 agent.tools.mode=READ_ONLY");
    }
    java.nio.file.Path root = properties.root();
    if (root.equals(agentProperties.workspace().toAbsolutePath().normalize())) {
      throw new IllegalStateException("agent.workspace-tools.root 不能复用 agent.workspace");
    }
    return WorkspaceReadOnlyToolset.enabled(root, properties.limits());
  }

  @Bean
  PromptTurnContextFactory promptTurnContextFactory(PromptProperties properties) {
    return new PromptTurnContextFactory(properties.zoneId());
  }

  @Bean(destroyMethod = "close")
  McpRuntime mcpRuntime(
      McpProperties mcpProperties,
      McpAssetProperties mcpAssetProperties,
      AgentProperties agentProperties) {
    Objects.requireNonNull(mcpAssetProperties, "mcpAssetProperties");
    var settings = mcpProperties.toSettings();
    if (settings.mode() == McpMode.DISABLED) {
      return McpRuntimes.disabled();
    }
    if (agentProperties.tools().mode() == ToolRuntimeMode.DISABLED) {
      throw new IllegalStateException("启用 MCP 前必须启用全局 Tool Runtime");
    }
    if (settings.requestTimeout().compareTo(agentProperties.tools().timeout()) >= 0) {
      throw new IllegalStateException("agent.mcp.request-timeout 必须小于 agent.tools.timeout");
    }
    if (settings.connectTimeout().compareTo(agentProperties.model().timeout()) >= 0) {
      throw new IllegalStateException("agent.mcp.connect-timeout 必须小于 agent.model.timeout");
    }
    var configuration = new McpConfigLoader().load(settings);
    return McpRuntimes.staticReadOnly(configuration, settings, mcpAssetProperties.toMode());
  }

  McpRuntime mcpRuntime(McpProperties mcpProperties, AgentProperties agentProperties) {
    return mcpRuntime(mcpProperties, new McpAssetProperties(null), agentProperties);
  }

  ChatUseCase chatUseCase(
      SessionRepository sessions,
      ChatModelPort model,
      SessionExecutionGate gate,
      TurnLifecycleObserver lifecycleObserver,
      ApprovalPort approvalPort,
      MemoryContextService memoryContext,
      McpRuntime mcpRuntime,
      WorkspaceReadOnlyToolset workspaceTools,
      SkillCatalogPort skillCatalog,
      SkillProperties skillProperties,
      AgentProperties properties,
      String modelName,
      String compatibilityPrompt,
      Resource systemPrompt)
      throws IOException {
    return chatUseCase(
        sessions,
        model,
        gate,
        lifecycleObserver,
        approvalPort,
        memoryContext,
        mcpRuntime,
        workspaceTools,
        skillCatalog,
        skillProperties,
        ConversationEvidenceToolset.disabled(),
        ConversationEvidenceContextFactory.disabled(),
        properties,
        modelName,
        compatibilityPrompt,
        systemPrompt);
  }

  ChatUseCase chatUseCase(
      SessionRepository sessions,
      ChatModelPort model,
      SessionExecutionGate gate,
      TurnLifecycleObserver lifecycleObserver,
      ApprovalPort approvalPort,
      MemoryContextService memoryContext,
      McpRuntime mcpRuntime,
      WorkspaceReadOnlyToolset workspaceTools,
      SkillCatalogPort skillCatalog,
      SkillProperties skillProperties,
      ConversationEvidenceToolset conversationEvidenceTools,
      ConversationEvidenceContextFactory conversationEvidenceContexts,
      AgentProperties properties,
      @Value("${spring.ai.openai.chat.model}") String modelName,
      @Value("${agent.compatibility.system-prompt-base64:}") String compatibilityPrompt,
      @Value("classpath:/prompts/system.md") Resource systemPrompt)
      throws IOException {
    return chatUseCase(
        sessions,
        model,
        gate,
        lifecycleObserver,
        approvalPort,
        memoryContext,
        mcpRuntime,
        workspaceTools,
        skillCatalog,
        skillProperties,
        conversationEvidenceTools,
        conversationEvidenceContexts,
        MemoryRecallToolset.disabled(),
        MemoryRecallContextFactory.disabled(),
        properties,
        modelName,
        compatibilityPrompt,
        systemPrompt);
  }

  @Bean
  ChatUseCase chatUseCase(
      SessionRepository sessions,
      ChatModelPort model,
      SessionExecutionGate gate,
      TurnLifecycleObserver lifecycleObserver,
      ApprovalPort approvalPort,
      MemoryContextService memoryContext,
      McpRuntime mcpRuntime,
      WorkspaceReadOnlyToolset workspaceTools,
      SkillCatalogPort skillCatalog,
      SkillProperties skillProperties,
      ConversationEvidenceToolset conversationEvidenceTools,
      ConversationEvidenceContextFactory conversationEvidenceContexts,
      MemoryRecallToolset memoryRecallTools,
      MemoryRecallContextFactory memoryRecallContexts,
      AgentProperties properties,
      @Value("${spring.ai.openai.chat.model}") String modelName,
      @Value("${agent.compatibility.system-prompt-base64:}") String compatibilityPrompt,
      @Value("classpath:/prompts/system.md") Resource systemPrompt)
      throws IOException {
    String prompt = systemPrompt(compatibilityPrompt, systemPrompt);
    ToolCatalog tools =
        configuredToolCatalog(
            properties,
            mcpRuntime,
            workspaceTools,
            skillCatalog,
            skillProperties,
            conversationEvidenceTools,
            memoryRecallTools);
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
            memoryContext,
            new ModelStreamingSettings(
                properties.model().maxDeltaEvents(), properties.model().maxDeltaCodePoints()),
            conversationEvidenceContexts,
            memoryRecallContexts);
    return new SafeChatUseCase(service, Clock.systemUTC());
  }

  ChatUseCase chatUseCase(
      SessionRepository sessions,
      ChatModelPort model,
      SessionExecutionGate gate,
      TurnLifecycleObserver lifecycleObserver,
      ApprovalPort approvalPort,
      MemoryContextService memoryContext,
      McpRuntime mcpRuntime,
      WorkspaceReadOnlyToolset workspaceTools,
      AgentProperties properties,
      String modelName,
      String compatibilityPrompt,
      Resource systemPrompt)
      throws IOException {
    return chatUseCase(
        sessions,
        model,
        gate,
        lifecycleObserver,
        approvalPort,
        memoryContext,
        mcpRuntime,
        workspaceTools,
        SkillCatalogPort.disabled(),
        defaultSkillProperties(),
        ConversationEvidenceToolset.disabled(),
        ConversationEvidenceContextFactory.disabled(),
        properties,
        modelName,
        compatibilityPrompt,
        systemPrompt);
  }

  ChatUseCase chatUseCase(
      SessionRepository sessions,
      ChatModelPort model,
      SessionExecutionGate gate,
      TurnLifecycleObserver lifecycleObserver,
      ApprovalPort approvalPort,
      MemoryContextService memoryContext,
      McpRuntime mcpRuntime,
      AgentProperties properties,
      String modelName,
      String compatibilityPrompt,
      Resource systemPrompt)
      throws IOException {
    return chatUseCase(
        sessions,
        model,
        gate,
        lifecycleObserver,
        approvalPort,
        memoryContext,
        mcpRuntime,
        WorkspaceReadOnlyToolset.disabled(),
        properties,
        modelName,
        compatibilityPrompt,
        systemPrompt);
  }

  MessageTurnService messageTurnService(ChatUseCase chat) {
    return new MessageTurnService(chat);
  }

  @Bean
  MessageTurnService messageTurnService(
      ChatUseCase chat, PluginRuntime plugins, PromptTurnContextFactory promptContexts) {
    return new MessageTurnService(chat, plugins.outboundMessageObserver(), promptContexts);
  }

  @Bean
  Clock cliClock() {
    return Clock.systemUTC();
  }

  @Bean
  CliIdGenerator cliIdGenerator() {
    return new SecureCliIdGenerator();
  }

  @Bean
  CliInput cliInput() {
    return new Utf8CliInput(System.in);
  }

  @Bean
  CliOutput cliOutput() {
    return new Utf8CliOutput(System.out, System.err);
  }

  @Bean
  CliThreadStarter cliThreadStarter() {
    return (name, task) -> Thread.ofVirtual().name(name).start(task);
  }

  @Bean(destroyMethod = "shutdown")
  LocalCliRunner localCliRunner(
      MessageTurnService turns,
      CliProperties properties,
      Clock cliClock,
      CliIdGenerator ids,
      CliInput input,
      CliOutput output,
      CliThreadStarter threadStarter) {
    return new LocalCliRunner(turns, properties, cliClock, ids, input, output, threadStarter);
  }

  ToolCatalog configuredToolCatalog(AgentProperties properties, McpRuntime mcpRuntime) {
    return configuredToolCatalog(
        properties,
        mcpRuntime,
        WorkspaceReadOnlyToolset.disabled(),
        SkillCatalogPort.disabled(),
        defaultSkillProperties(),
        ConversationEvidenceToolset.disabled());
  }

  ToolCatalog configuredToolCatalog(
      AgentProperties properties, McpRuntime mcpRuntime, WorkspaceReadOnlyToolset workspaceTools) {
    return configuredToolCatalog(
        properties,
        mcpRuntime,
        workspaceTools,
        SkillCatalogPort.disabled(),
        defaultSkillProperties(),
        ConversationEvidenceToolset.disabled());
  }

  ToolCatalog configuredToolCatalog(
      AgentProperties properties,
      McpRuntime mcpRuntime,
      WorkspaceReadOnlyToolset workspaceTools,
      SkillCatalogPort skillCatalog,
      SkillProperties skillProperties) {
    return configuredToolCatalog(
        properties,
        mcpRuntime,
        workspaceTools,
        skillCatalog,
        skillProperties,
        ConversationEvidenceToolset.disabled());
  }

  ToolCatalog configuredToolCatalog(
      AgentProperties properties,
      McpRuntime mcpRuntime,
      WorkspaceReadOnlyToolset workspaceTools,
      SkillCatalogPort skillCatalog,
      SkillProperties skillProperties,
      ConversationEvidenceToolset conversationEvidenceTools) {
    return configuredToolCatalog(
        properties,
        mcpRuntime,
        workspaceTools,
        skillCatalog,
        skillProperties,
        conversationEvidenceTools,
        MemoryRecallToolset.disabled());
  }

  ToolCatalog configuredToolCatalog(
      AgentProperties properties,
      McpRuntime mcpRuntime,
      WorkspaceReadOnlyToolset workspaceTools,
      SkillCatalogPort skillCatalog,
      SkillProperties skillProperties,
      ConversationEvidenceToolset conversationEvidenceTools,
      MemoryRecallToolset memoryRecallTools) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(mcpRuntime, "mcpRuntime");
    Objects.requireNonNull(workspaceTools, "workspaceTools");
    Objects.requireNonNull(skillCatalog, "skillCatalog");
    Objects.requireNonNull(skillProperties, "skillProperties");
    Objects.requireNonNull(conversationEvidenceTools, "conversationEvidenceTools");
    Objects.requireNonNull(memoryRecallTools, "memoryRecallTools");
    Tool skillTool = readSkillTool(properties, skillCatalog, skillProperties);
    if (properties.tools().mode() == ToolRuntimeMode.DISABLED) {
      return new ToolCatalog(List.of());
    }
    List<ToolCatalogEntry> tools = new ArrayList<>();
    tools.add(
        new ToolCatalogEntry(
            new CurrentTimeTool(Clock.systemUTC()),
            ToolCatalogVisibility.ALWAYS_ON,
            ToolCatalogSource.BUILTIN,
            "",
            List.of("当前时间", "UTC")));
    if (skillTool != null) {
      tools.add(
          new ToolCatalogEntry(
              skillTool,
              ToolCatalogVisibility.DEFERRED,
              ToolCatalogSource.BUILTIN,
              "",
              List.of("skill", "技能", "instruction")));
    }
    for (Tool tool : conversationEvidenceTools.tools()) {
      if (tool.definition().risk() != ToolRisk.READ_ONLY) {
        throw new IllegalStateException("Conversation Evidence Runtime 暴露了非只读工具");
      }
      tools.add(
          new ToolCatalogEntry(
              tool,
              ToolCatalogVisibility.DEFERRED,
              ToolCatalogSource.BUILTIN,
              "",
              List.of("历史消息", "conversation", "evidence", "search")));
    }
    for (Tool tool : memoryRecallTools.tools()) {
      if (tool.definition().risk() != ToolRisk.READ_ONLY) {
        throw new IllegalStateException("Memory Recall Runtime 暴露了非只读工具");
      }
      tools.add(
          new ToolCatalogEntry(
              tool,
              ToolCatalogVisibility.DEFERRED,
              ToolCatalogSource.BUILTIN,
              "",
              List.of("记忆", "memory", "recall")));
    }
    for (Tool tool : workspaceTools.tools()) {
      if (tool.definition().risk() != ToolRisk.READ_ONLY) {
        throw new IllegalStateException("Workspace Runtime 暴露了非只读工具");
      }
      tools.add(
          new ToolCatalogEntry(
              tool,
              ToolCatalogVisibility.DEFERRED,
              ToolCatalogSource.BUILTIN,
              "",
              List.of("文件", "目录", "workspace")));
    }
    for (Tool tool : mcpRuntime.tools()) {
      if (tool.definition().risk() != ToolRisk.READ_ONLY) {
        throw new IllegalStateException("MCP Runtime 暴露了非只读工具");
      }
      tools.add(
          new ToolCatalogEntry(
              tool,
              ToolCatalogVisibility.DEFERRED,
              ToolCatalogSource.MCP,
              mcpSourceId(tool.definition().name()),
              List.of()));
    }
    return new ToolCatalog(tools);
  }

  private static Tool readSkillTool(
      AgentProperties properties, SkillCatalogPort skillCatalog, SkillProperties skillProperties) {
    if (skillProperties.mode() == SkillCatalogMode.DISABLED) {
      return null;
    }
    if (properties.tools().mode() != ToolRuntimeMode.READ_ONLY) {
      return null;
    }
    if (skillProperties.maxReadCodePoints() > properties.tools().maxResultCharacters()) {
      throw new IllegalStateException(
          "agent.skills.max-read-code-points 不能大于 agent.tools.max-result-characters");
    }
    return new ReadSkillTool(skillCatalog, skillProperties.maxReadCodePoints());
  }

  private static SkillProperties defaultSkillProperties() {
    return new SkillProperties("DISABLED", "", 64, 65_536, 32_768, 32_768, 20_000);
  }

  private static boolean conversationEvidenceEnabled(
      AgentProperties agentProperties, ConversationEvidenceProperties properties) {
    if (agentProperties.tools().mode() == ToolRuntimeMode.DISABLED
        || properties.toMode()
            == io.namei.agent.kernel.evidence.ConversationEvidenceMode.DISABLED) {
      return false;
    }
    if (agentProperties.tools().mode() != ToolRuntimeMode.READ_ONLY) {
      throw new IllegalStateException(
          "只读 Conversation Evidence Tool 要求 agent.tools.mode=READ_ONLY");
    }
    return true;
  }

  private static boolean memoryRecallEnabled(
      AgentProperties agentProperties, MemoryRecallProperties properties) {
    if (agentProperties.tools().mode() == ToolRuntimeMode.DISABLED
        || properties.toMode() == io.namei.agent.kernel.memory.MemoryRecallMode.DISABLED
        || agentProperties.memory().mode()
            != io.namei.agent.kernel.memory.MemoryRuntimeMode.JAVA_NATIVE) {
      return false;
    }
    if (agentProperties.tools().mode() != ToolRuntimeMode.READ_ONLY) {
      throw new IllegalStateException("只读 Memory Recall Tool 要求 agent.tools.mode=READ_ONLY");
    }
    return true;
  }

  private static SemanticMemoryRetrievalSettings semanticMemoryRetrievalSettings(
      AgentProperties properties) {
    AgentProperties.Memory memory = properties.memory();
    AgentProperties.Retrieval retrieval = memory.retrieval();
    return new SemanticMemoryRetrievalSettings(
        memory.embedding().model(),
        memory.embedding().dimensions(),
        retrieval.topK(),
        retrieval.scoreThreshold(),
        retrieval.hotnessAlpha(),
        retrieval.hotnessHalfLifeDays(),
        retrieval.maxCandidates(),
        retrieval.maxInjectedCharacters());
  }

  List<Tool> configuredTools(AgentProperties properties, McpRuntime mcpRuntime) {
    return configuredTools(properties, mcpRuntime, WorkspaceReadOnlyToolset.disabled());
  }

  List<Tool> configuredTools(
      AgentProperties properties, McpRuntime mcpRuntime, WorkspaceReadOnlyToolset workspaceTools) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(mcpRuntime, "mcpRuntime");
    Objects.requireNonNull(workspaceTools, "workspaceTools");
    if (properties.tools().mode() == ToolRuntimeMode.DISABLED) {
      return List.of();
    }
    var tools = new ArrayList<Tool>();
    tools.add(new CurrentTimeTool(Clock.systemUTC()));
    for (Tool tool : workspaceTools.tools()) {
      if (tool.definition().risk() != ToolRisk.READ_ONLY) {
        throw new IllegalStateException("Workspace Runtime 暴露了非只读工具");
      }
      tools.add(tool);
    }
    for (Tool tool : mcpRuntime.tools()) {
      if (tool.definition().risk() != ToolRisk.READ_ONLY) {
        throw new IllegalStateException("MCP Runtime 暴露了非只读工具");
      }
      tools.add(tool);
    }
    return List.copyOf(tools);
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

  private static <T> T required(ObjectProvider<T> provider) {
    T value = provider.getIfUnique();
    if (value == null) {
      throw new IllegalStateException("JAVA_NATIVE 记忆装配不完整");
    }
    return value;
  }

  private static String mcpSourceId(String toolName) {
    Objects.requireNonNull(toolName, "toolName");
    if (!toolName.startsWith("mcp_")) {
      return toolName;
    }
    int separator = toolName.indexOf("__", "mcp_".length());
    if (separator <= "mcp_".length()) {
      return toolName;
    }
    return toolName.substring("mcp_".length(), separator);
  }
}
