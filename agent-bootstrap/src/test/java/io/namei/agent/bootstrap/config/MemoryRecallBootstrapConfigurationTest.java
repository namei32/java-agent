package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.application.MemoryRecallToolset;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.memory.MemoryRuntimeMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;

class MemoryRecallBootstrapConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void disabledAndNonJavaNativeModesRegisterNoRecallTool() {
    var configuration = new ApplicationConfiguration();

    assertThat(
            configuration
                .memoryRecallToolset(
                    agent(ToolRuntimeMode.READ_ONLY, MemoryRuntimeMode.JAVA_NATIVE, 20_000),
                    new MemoryRecallProperties("DISABLED"))
                .tools())
        .isEmpty();
    assertThat(
            configuration
                .memoryRecallToolset(
                    agent(ToolRuntimeMode.READ_ONLY, MemoryRuntimeMode.READ_ONLY, 20_000),
                    new MemoryRecallProperties("CURRENT_SCOPE_READ_ONLY"))
                .tools())
        .isEmpty();
  }

  @Test
  void explicitReadOnlyJavaNativeModeRegistersOneDeferredBuiltin() {
    var configuration = new ApplicationConfiguration();
    AgentProperties agent = agent(ToolRuntimeMode.READ_ONLY, MemoryRuntimeMode.JAVA_NATIVE, 20_000);
    MemoryRecallToolset tools =
        configuration.memoryRecallToolset(
            agent, new MemoryRecallProperties("CURRENT_SCOPE_READ_ONLY"));

    assertThat(tools.tools())
        .extracting(tool -> tool.definition().name())
        .containsExactly("recall_memory");
    assertThat(
            configuration
                .configuredToolCatalog(
                    agent,
                    McpRuntimes.disabled(),
                    io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset.disabled(),
                    io.namei.agent.kernel.skill.SkillCatalogPort.disabled(),
                    new SkillProperties("DISABLED", "", 64, 65_536, 32_768, 32_768),
                    io.namei.agent.application.ConversationEvidenceToolset.disabled(),
                    tools)
                .initialDefinitions())
        .extracting(definition -> definition.name())
        .containsExactly("current_time", "tool_search");
  }

  @Test
  void globalDisabledWinsButUnsafeModesAndBudgetsFailClosed() {
    var configuration = new ApplicationConfiguration();
    var requested = new MemoryRecallProperties("CURRENT_SCOPE_READ_ONLY");

    assertThat(
            configuration
                .memoryRecallToolset(
                    agent(ToolRuntimeMode.DISABLED, MemoryRuntimeMode.JAVA_NATIVE, 20_000),
                    requested)
                .tools())
        .isEmpty();
    assertThatThrownBy(
            () ->
                configuration.memoryRecallToolset(
                    agent(ToolRuntimeMode.APPROVAL_REQUIRED, MemoryRuntimeMode.JAVA_NATIVE, 20_000),
                    requested))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("只读 Memory Recall Tool 要求 agent.tools.mode=READ_ONLY");
    assertThatThrownBy(
            () ->
                configuration.memoryRecallToolset(
                    agent(ToolRuntimeMode.READ_ONLY, MemoryRuntimeMode.JAVA_NATIVE, 11_999),
                    requested))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("agent.memory-recall 单项预算不能大于 agent.tools.max-result-characters");
  }

  @Test
  void springChatUseCaseBeanReceivesTheRecallToolsetAndContextFactory() {
    var beanMethod =
        Arrays.stream(ApplicationConfiguration.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("chatUseCase"))
            .filter(method -> method.isAnnotationPresent(Bean.class))
            .findFirst()
            .orElseThrow();

    assertThat(beanMethod.getParameterTypes())
        .contains(
            MemoryRecallToolset.class, io.namei.agent.application.MemoryRecallContextFactory.class);
  }

  private AgentProperties agent(
      ToolRuntimeMode toolMode, MemoryRuntimeMode memoryMode, int maxResultCharacters) {
    return new AgentProperties(
        temporaryDirectory.resolve(
            "workspace-" + toolMode + "-" + memoryMode + "-" + maxResultCharacters),
        null,
        new AgentProperties.Model(Duration.ofSeconds(60)),
        null,
        new AgentProperties.Tools(
            toolMode, 8, 16, Duration.ofSeconds(5), 32, 16_384, maxResultCharacters),
        new AgentProperties.Memory(memoryMode, 65_536, 100_000, 20_000, null, null));
  }
}
