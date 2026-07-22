package io.namei.agent.kernel.mcp;

/** MCP Resource 与 Prompt Catalog 发现的显式激活边界。 */
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
