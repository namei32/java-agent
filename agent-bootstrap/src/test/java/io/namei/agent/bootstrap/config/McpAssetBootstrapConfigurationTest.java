package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpRuntime;
import io.namei.agent.adapter.mcp.McpRuntimeStatus;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import io.namei.agent.kernel.mcp.McpAssetContractViolation;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpAssetBootstrapConfigurationTest {
  @TempDir Path temp;

  @Test
  void defaultsToDisabledAndRejectsRelaxedAssetModeValues() {
    assertThat(new McpAssetProperties(null).toMode()).isEqualTo(McpAssetCatalogMode.DISABLED);
    assertThatThrownBy(() -> new McpAssetProperties("catalog_only"))
        .isInstanceOf(McpAssetContractViolation.class);
  }

  @Test
  void disabledMcpDoesNotStartAnAssetCatalogEvenWhenCatalogOnlyIsConfigured() {
    McpProperties mcp =
        new McpProperties(
            null,
            temp.resolve("must-not-be-read.json"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    McpRuntime runtime =
        new ApplicationConfiguration()
            .mcpRuntime(mcp, new McpAssetProperties("CATALOG_ONLY"), agentProperties());

    assertThat(runtime.status()).isEqualTo(McpRuntimeStatus.disabled());
    assertThat(runtime.assets().descriptors()).isEmpty();
  }

  @Test
  void templatesKeepAssetsDisabled() throws Exception {
    assertThat(java.nio.file.Files.readString(Path.of("src/main/resources/application.yml")))
        .contains("mode: ${AGENT_MCP_ASSETS_MODE:DISABLED}");
    assertThat(java.nio.file.Files.readString(Path.of("../.env.example")))
        .contains("AGENT_MCP_ASSETS_MODE=DISABLED");
  }

  private AgentProperties agentProperties() {
    return new AgentProperties(
        temp.resolve("workspace"),
        null,
        new AgentProperties.Model(Duration.ofSeconds(60)),
        null,
        new AgentProperties.Tools(
            ToolRuntimeMode.READ_ONLY, 8, 16, Duration.ofSeconds(5), 32, 16_384, 20_000),
        null);
  }
}
