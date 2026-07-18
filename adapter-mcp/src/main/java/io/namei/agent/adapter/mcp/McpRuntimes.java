package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import io.namei.agent.kernel.port.Tool;
import java.util.List;

/** Safe runtime factories that do not require SDK types at the public boundary. */
public final class McpRuntimes {
  private McpRuntimes() {}

  public static McpRuntime disabled() {
    return DisabledRuntime.INSTANCE;
  }

  public static McpRuntime staticReadOnly(McpConfiguration configuration, McpSettings settings) {
    return staticReadOnly(configuration, settings, McpAssetCatalogMode.DISABLED);
  }

  public static McpRuntime staticReadOnly(
      McpConfiguration configuration, McpSettings settings, McpAssetCatalogMode assetsMode) {
    return new DefaultMcpRuntime(configuration, settings, assetsMode);
  }

  private enum DisabledRuntime implements McpRuntime {
    INSTANCE;

    @Override
    public List<Tool> tools() {
      return List.of();
    }

    @Override
    public McpRuntimeStatus status() {
      return McpRuntimeStatus.disabled();
    }

    @Override
    public void close() {}
  }
}
