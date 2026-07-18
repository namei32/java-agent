package io.namei.agent.kernel.mcp;

/** Project-owned metadata-only boundary for MCP Resource and Prompt discovery. */
@FunctionalInterface
public interface McpAssetCatalogPort {
  McpAssetCatalog snapshot();

  static McpAssetCatalogPort disabled() {
    return McpAssetCatalog::empty;
  }
}
