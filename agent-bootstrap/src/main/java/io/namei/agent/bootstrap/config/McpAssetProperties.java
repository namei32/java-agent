package io.namei.agent.bootstrap.config;

import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strict, default-disabled activation for metadata-only MCP Resource and Prompt discovery. */
@ConfigurationProperties("agent.mcp.assets")
public record McpAssetProperties(String mode) {
  public McpAssetProperties {
    mode = mode == null ? McpAssetCatalogMode.DISABLED.name() : mode;
    McpAssetCatalogMode.parse(mode);
  }

  McpAssetCatalogMode toMode() {
    return McpAssetCatalogMode.parse(mode);
  }

  @Override
  public String toString() {
    return "McpAssetProperties[mode=" + mode + "]";
  }
}
