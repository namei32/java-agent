package io.namei.agent.kernel.mcp;

/** Metadata-only MCP catalog kinds. Their bodies are intentionally outside the Kernel model. */
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
