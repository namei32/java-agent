package io.namei.agent.kernel.mcp;

/** Fail-closed contract violation that intentionally excludes untrusted asset content. */
public final class McpAssetContractViolation extends IllegalArgumentException {
  private final McpAssetStableCode code;

  McpAssetContractViolation(McpAssetStableCode code) {
    super(code.name());
    this.code = code;
  }

  public McpAssetStableCode code() {
    return code;
  }
}
