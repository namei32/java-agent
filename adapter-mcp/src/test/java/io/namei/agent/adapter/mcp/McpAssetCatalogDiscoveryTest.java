package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.mcp.McpAssetCatalog;
import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import io.namei.agent.kernel.mcp.McpAssetDescriptor;
import io.namei.agent.kernel.port.Tool;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpAssetCatalogDiscoveryTest {
  @TempDir Path temp;

  @Test
  void discoversOnlyBoundedResourceAndPromptMetadataFromDeclaredCapabilities() {
    try (McpStdioClient client =
        new McpStdioClient(
            McpReferenceServerSupport.server(temp, "assets-paginated"),
            McpReferenceServerSupport.settings(temp, 1_048_576, 1))) {
      McpAssetCatalog catalog = client.initializeAndDiscoverAssets("docs");

      assertThat(catalog.descriptors())
          .extracting(McpAssetDescriptor::localName)
          .containsExactly(
              "mcp_docs__resource_81cda059f0d5196f",
              "mcp_docs__resource_fb15ad9d9fa88419",
              "mcp_docs__prompt_release_notes");
      assertThat(catalog.descriptors())
          .extracting(McpAssetDescriptor::description)
          .containsExactly("Second page resource", "First page resource", "Release summaries");
    }
  }

  @Test
  void capabilityAbsenceProducesAnEmptyCatalogWithoutTryingToReadAssetBodies() {
    try (McpStdioClient client =
        new McpStdioClient(
            McpReferenceServerSupport.server(temp, "tools-only"),
            McpReferenceServerSupport.settings(temp, 1_048_576, 1))) {
      assertThat(client.initializeAndDiscoverAssets("docs")).isEqualTo(McpAssetCatalog.empty());
    }
  }

  @Test
  void isolatesMoreThanThirtyTwoPromptsAsAnUnavailableAssetCatalog() {
    try (McpStdioClient client =
        new McpStdioClient(
            McpReferenceServerSupport.server(temp, "assets-too-many-prompts"),
            McpReferenceServerSupport.settings(temp, 1_048_576, 1))) {
      assertThat(client.initializeAndDiscoverAssets("docs")).isEqualTo(McpAssetCatalog.empty());
    }
  }

  @Test
  void assetDiscoveryFailureDoesNotRemoveTheExistingReadOnlyToolCatalog() {
    McpConfiguration configuration =
        new McpConfiguration(
            1, List.of(McpReferenceServerSupport.server(temp, "assets-too-many-prompts")));

    try (McpRuntime runtime =
        McpRuntimes.staticReadOnly(
            configuration,
            McpReferenceServerSupport.settings(temp, 1_048_576, 1),
            McpAssetCatalogMode.CATALOG_ONLY)) {
      assertThat(runtime.status().state()).isEqualTo(McpRuntimeStatus.State.READY);
      assertThat(runtime.tools()).isNotEmpty();
      assertThat(runtime.assets().descriptors()).isEmpty();
    }
  }

  @Test
  void resourceListChangedMakesTheAssetsCatalogStaleWithoutRefreshingIt() {
    McpConfiguration configuration =
        new McpConfiguration(
            1, List.of(McpReferenceServerSupport.server(temp, "assets-list-changed")));

    try (McpRuntime runtime =
        McpRuntimes.staticReadOnly(
            configuration,
            McpReferenceServerSupport.settings(temp, 1_048_576, 1),
            McpAssetCatalogMode.CATALOG_ONLY)) {
      Tool echo =
          runtime.tools().stream()
              .filter(tool -> tool.definition().name().endsWith("echo"))
              .findFirst()
              .orElseThrow();
      assertThat(runtime.assets().descriptors()).hasSize(3);

      echo.execute(Map.of("text", "stale"));

      assertThat(runtime.status().staleServers()).isEqualTo(1);
      assertThat(runtime.assets().descriptors()).isEmpty();
    }
  }

  @Test
  void runtimeLeavesAssetsEmptyUntilCatalogOnlyIsExplicitlySelected() {
    McpConfiguration configuration =
        new McpConfiguration(
            1, List.of(McpReferenceServerSupport.server(temp, "assets-paginated")));
    McpSettings settings = McpReferenceServerSupport.settings(temp, 1_048_576, 1);

    try (McpRuntime defaultRuntime = McpRuntimes.staticReadOnly(configuration, settings);
        McpRuntime catalogRuntime =
            McpRuntimes.staticReadOnly(configuration, settings, McpAssetCatalogMode.CATALOG_ONLY)) {
      assertThat(defaultRuntime.assets()).isEqualTo(McpAssetCatalog.empty());
      assertThat(catalogRuntime.assets().descriptors())
          .extracting(McpAssetDescriptor::localName)
          .containsExactly(
              "mcp_docs__resource_81cda059f0d5196f",
              "mcp_docs__resource_fb15ad9d9fa88419",
              "mcp_docs__prompt_release_notes");
    }
  }

  @Test
  void defaultStaticMcpDoesNotIssueAnyAssetListRequest() {
    McpConfiguration configuration =
        new McpConfiguration(
            1, List.of(McpReferenceServerSupport.server(temp, "assets-disabled-probe")));

    try (McpRuntime runtime =
        McpRuntimes.staticReadOnly(
            configuration, McpReferenceServerSupport.settings(temp, 1_048_576, 1))) {
      Tool echo =
          runtime.tools().stream()
              .filter(tool -> tool.definition().name().endsWith("echo"))
              .findFirst()
              .orElseThrow();

      assertThat(echo.execute(Map.of("text", "default")).content()).isEqualTo("default");
      assertThat(runtime.assets()).isEqualTo(McpAssetCatalog.empty());
    }
  }
}
