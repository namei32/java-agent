package io.namei.agent.kernel.mcp;

/** 有意排除不可信 Asset 内容的关闭式失败契约违规异常。 */
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
