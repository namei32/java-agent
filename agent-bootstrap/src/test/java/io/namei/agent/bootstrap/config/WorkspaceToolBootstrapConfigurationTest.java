package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpRuntimes;
import io.namei.agent.adapter.workspace.WorkspaceReadOnlyToolset;
import io.namei.agent.application.ToolRuntimeMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceToolBootstrapConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void disabledWorkspaceToolsDoNotParseOrCreateAConfiguredRoot() {
    Path missingRoot = temporaryDirectory.resolve("must-remain-missing");
    WorkspaceToolProperties properties =
        new WorkspaceToolProperties(
            "DISABLED", missingRoot.toString(), 1_000_000, 400, 10_000, 20_000, 256);

    WorkspaceReadOnlyToolset toolset =
        new ApplicationConfiguration()
            .workspaceReadOnlyToolset(agent(ToolRuntimeMode.READ_ONLY), properties);

    assertThat(toolset.tools()).isEmpty();
    assertThat(missingRoot).doesNotExist();
  }

  @Test
  void explicitReadOnlyRootRegistersOnlyDeferredBuiltins() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("workspace-tools-root"));
    WorkspaceToolProperties properties =
        new WorkspaceToolProperties(
            "READ_ONLY", root.toString(), 1_000_000, 400, 10_000, 20_000, 256);
    var configuration = new ApplicationConfiguration();
    WorkspaceReadOnlyToolset toolset =
        configuration.workspaceReadOnlyToolset(agent(ToolRuntimeMode.READ_ONLY), properties);

    assertThat(
            configuration
                .configuredToolCatalog(
                    agent(ToolRuntimeMode.READ_ONLY), McpRuntimes.disabled(), toolset)
                .initialDefinitions())
        .extracting(definition -> definition.name())
        .containsExactly("current_time", "tool_search");
    assertThat(
            configuration.configuredTools(
                agent(ToolRuntimeMode.READ_ONLY), McpRuntimes.disabled(), toolset))
        .extracting(tool -> tool.definition().name())
        .containsExactly("current_time", "read_file", "list_dir");
  }

  @Test
  @Tag("failure")
  void rejectsRelativeRootsAndGlobalModesThatCouldBypassTheReadOnlyBoundary() {
    assertThatThrownBy(
            () ->
                new WorkspaceToolProperties(
                        "READ_ONLY", "relative-root", 1_000_000, 400, 10_000, 20_000, 256)
                    .root())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("agent.workspace-tools.root 必须是绝对路径");

    WorkspaceToolProperties configured =
        new WorkspaceToolProperties(
            "READ_ONLY",
            temporaryDirectory.resolve("not-read").toString(),
            1_000_000,
            400,
            10_000,
            20_000,
            256);
    var configuration = new ApplicationConfiguration();
    for (ToolRuntimeMode mode :
        new ToolRuntimeMode[] {ToolRuntimeMode.DISABLED, ToolRuntimeMode.APPROVAL_REQUIRED}) {
      assertThatThrownBy(() -> configuration.workspaceReadOnlyToolset(agent(mode), configured))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("只读 Workspace Tool 要求 agent.tools.mode=READ_ONLY");
    }
  }

  @Test
  @Tag("failure")
  void rejectsReusingTheJavaRuntimeWorkspaceAsTheToolRoot() throws Exception {
    AgentProperties agent = agent(ToolRuntimeMode.READ_ONLY);
    Files.createDirectory(agent.workspace());
    WorkspaceToolProperties properties =
        new WorkspaceToolProperties(
            "READ_ONLY", agent.workspace().toString(), 1_000_000, 400, 10_000, 20_000, 256);

    assertThatThrownBy(
            () -> new ApplicationConfiguration().workspaceReadOnlyToolset(agent, properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("agent.workspace-tools.root 不能复用 agent.workspace");
  }

  private AgentProperties agent(ToolRuntimeMode mode) {
    return new AgentProperties(
        temporaryDirectory.resolve("agent-workspace"),
        null,
        new AgentProperties.Model(Duration.ofSeconds(60)),
        null,
        new AgentProperties.Tools(mode, 8, 16, Duration.ofSeconds(5), 32, 16_384, 20_000),
        null);
  }
}
