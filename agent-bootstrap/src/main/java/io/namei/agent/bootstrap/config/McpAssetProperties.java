package io.namei.agent.bootstrap.config;

import io.namei.agent.kernel.mcp.McpAssetCatalogMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅元数据 MCP Resource 与 Prompt 发现的严格、默认禁用激活配置。 */
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
