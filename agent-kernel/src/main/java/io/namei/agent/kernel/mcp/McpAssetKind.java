package io.namei.agent.kernel.mcp;

/** 仅元数据的 MCP Catalog 类型；其正文有意置于 Kernel 模型之外。 */
public enum McpAssetKind {
  RESOURCE,
  PROMPT;

  public static McpAssetKind parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw McpAssetContract.violation();
    }
  }
}
