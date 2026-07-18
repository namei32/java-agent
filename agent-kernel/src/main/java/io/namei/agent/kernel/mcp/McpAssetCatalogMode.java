package io.namei.agent.kernel.mcp;

/** Explicit activation boundary for MCP resource and prompt catalog discovery. */
public enum McpAssetCatalogMode {
  DISABLED,
  CATALOG_ONLY;

  public static McpAssetCatalogMode parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw McpAssetContract.violation();
    }
  }
}
