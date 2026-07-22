package io.namei.agent.kernel.mcp;

/** 项目自持、仅元数据的 MCP Resource 与 Prompt 发现边界。 */
@FunctionalInterface
public interface McpAssetCatalogPort {
  McpAssetCatalog snapshot();

  static McpAssetCatalogPort disabled() {
    return McpAssetCatalog::empty;
  }
}
