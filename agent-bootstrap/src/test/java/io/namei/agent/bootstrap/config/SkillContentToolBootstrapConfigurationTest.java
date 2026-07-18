package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.skill.SkillCatalogPort;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillContentToolBootstrapConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void registersReadSkillOnlyAsADeferredToolWhenBothModesAreReadOnly() {
    var configuration = new ApplicationConfiguration();
    var catalog =
        configuration.configuredToolCatalog(
            agent(ToolRuntimeMode.READ_ONLY, 100),
            McpRuntimes.disabled(),
            WorkspaceReadOnlyToolset.disabled(),
            SkillCatalogPort.disabled(),
            skills("READ_ONLY", 100));

    assertThat(catalog.initialDefinitions())
        .extracting(definition -> definition.name())
        .containsExactly("current_time", "tool_search");
  }

  @Test
  void disabledSkillsDoNotRegisterReadSkillOrReadTheCatalog() {
    var configuration = new ApplicationConfiguration();
    SkillCatalogPort poisoned =
        () -> {
          throw new AssertionError("DISABLED 不得读取 Skill Catalog");
        };

    var catalog =
        configuration.configuredToolCatalog(
            agent(ToolRuntimeMode.READ_ONLY, 100),
            McpRuntimes.disabled(),
            WorkspaceReadOnlyToolset.disabled(),
            poisoned,
            skills("DISABLED", 100));

    assertThat(catalog.initialDefinitions())
        .extracting(definition -> definition.name())
        .containsExactly("current_time");
  }

  @Test
  void skipsReadSkillOutsideReadOnlyModeAndRejectsOnlyAnUnsafeReadBudget() {
    var configuration = new ApplicationConfiguration();
    assertThat(
            configuration
                .configuredToolCatalog(
                    agent(ToolRuntimeMode.DISABLED, 100),
                    McpRuntimes.disabled(),
                    WorkspaceReadOnlyToolset.disabled(),
                    SkillCatalogPort.disabled(),
                    skills("READ_ONLY", 100))
                .initialDefinitions())
        .isEmpty();
    assertThat(
            configuration
                .configuredToolCatalog(
                    agent(ToolRuntimeMode.APPROVAL_REQUIRED, 100),
                    McpRuntimes.disabled(),
                    WorkspaceReadOnlyToolset.disabled(),
                    SkillCatalogPort.disabled(),
                    skills("READ_ONLY", 100))
                .initialDefinitions())
        .extracting(definition -> definition.name())
        .containsExactly("current_time");
    assertThatThrownBy(
            () ->
                configuration.configuredToolCatalog(
                    agent(ToolRuntimeMode.READ_ONLY, 99),
                    McpRuntimes.disabled(),
                    WorkspaceReadOnlyToolset.disabled(),
                    SkillCatalogPort.disabled(),
                    skills("READ_ONLY", 100)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("agent.skills.max-read-code-points 不能大于 agent.tools.max-result-characters");
  }

  private AgentProperties agent(ToolRuntimeMode mode, int maxResultCharacters) {
    return new AgentProperties(
        temporaryDirectory.resolve("workspace"),
        null,
        new AgentProperties.Model(Duration.ofSeconds(60)),
        null,
        new AgentProperties.Tools(
            mode, 8, 16, Duration.ofSeconds(5), 32, 16_384, maxResultCharacters),
        null);
  }

  private static SkillProperties skills(String mode, int maxReadCodePoints) {
    return new SkillProperties(mode, "", 64, 65_536, 32_768, 32_768, maxReadCodePoints);
  }
}
