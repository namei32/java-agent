package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.application.ConversationEvidenceToolset;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.port.ConversationEvidencePort;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;

class ConversationEvidenceBootstrapConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void disabledModeRegistersNoToolsAndDoesNotEnableTheStorePort() {
    var configuration = new ApplicationConfiguration();
    AgentProperties agent = agent(ToolRuntimeMode.READ_ONLY, 20_000);
    var properties = new ConversationEvidenceProperties("DISABLED");
    ConversationEvidencePort port =
        configuration.conversationEvidencePort(
            agent, properties, configuration.sqliteSchema(agent));
    ConversationEvidenceToolset tools =
        configuration.conversationEvidenceToolset(agent, properties);

    assertThat(tools.tools()).isEmpty();
    assertThat(
            configuration
                .configuredToolCatalog(
                    agent,
                    McpRuntimes.disabled(),
                    io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset.disabled(),
                    io.namei.agent.kernel.skill.SkillCatalogPort.disabled(),
                    new SkillProperties("DISABLED", "", 64, 65_536, 32_768, 32_768),
                    tools)
                .initialDefinitions())
        .extracting(definition -> definition.name())
        .containsExactly("current_time");
    assertThatThrownBy(() -> port.fetch("private", java.util.List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("会话证据读取未启用");
  }

  @Test
  void explicitReadOnlyModeRegistersOnlyDeferredBuiltins() {
    var configuration = new ApplicationConfiguration();
    AgentProperties agent = agent(ToolRuntimeMode.READ_ONLY, 20_000);
    var properties = new ConversationEvidenceProperties("CURRENT_SESSION_READ_ONLY");
    ConversationEvidenceToolset tools =
        configuration.conversationEvidenceToolset(agent, properties);

    assertThat(tools.tools())
        .extracting(tool -> tool.definition().name())
        .containsExactly("fetch_messages", "search_messages");
    assertThat(
            configuration
                .configuredToolCatalog(
                    agent,
                    McpRuntimes.disabled(),
                    io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset.disabled(),
                    io.namei.agent.kernel.skill.SkillCatalogPort.disabled(),
                    new SkillProperties("DISABLED", "", 64, 65_536, 32_768, 32_768),
                    tools)
                .initialDefinitions())
        .extracting(definition -> definition.name())
        .containsExactly("current_time", "tool_search");
  }

  @Test
  void globalDisabledStaysDisabledButUnsafeModesAndBudgetsFailClosed() {
    var configuration = new ApplicationConfiguration();
    var requested = new ConversationEvidenceProperties("CURRENT_SESSION_READ_ONLY");
    AgentProperties disabled = agent(ToolRuntimeMode.DISABLED, 20_000);

    assertThat(configuration.conversationEvidenceToolset(disabled, requested).tools()).isEmpty();
    assertThatThrownBy(
            () ->
                configuration.conversationEvidenceToolset(
                    agent(ToolRuntimeMode.APPROVAL_REQUIRED, 20_000), requested))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("只读 Conversation Evidence Tool 要求 agent.tools.mode=READ_ONLY");
    assertThatThrownBy(
            () ->
                configuration.conversationEvidencePort(
                    agent(ToolRuntimeMode.READ_ONLY, 11_999),
                    requested,
                    configuration.sqliteSchema(agent(ToolRuntimeMode.READ_ONLY, 11_999))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("agent.conversation-evidence 单项预算不能大于 agent.tools.max-result-characters");
  }

  @Test
  void springChatUseCaseBeanReceivesTheEvidenceToolsetAndContextFactory() {
    var beanMethod =
        Arrays.stream(ApplicationConfiguration.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("chatUseCase"))
            .filter(method -> method.isAnnotationPresent(Bean.class))
            .findFirst()
            .orElseThrow();

    assertThat(beanMethod.getParameterTypes())
        .contains(
            ConversationEvidenceToolset.class,
            io.namei.agent.application.ConversationEvidenceContextFactory.class);
  }

  private AgentProperties agent(ToolRuntimeMode mode, int maxResultCharacters) {
    return new AgentProperties(
        temporaryDirectory.resolve("workspace-" + mode + "-" + maxResultCharacters),
        null,
        new AgentProperties.Model(Duration.ofSeconds(60)),
        null,
        new AgentProperties.Tools(
            mode, 8, 16, Duration.ofSeconds(5), 32, 16_384, maxResultCharacters),
        null);
  }
}
