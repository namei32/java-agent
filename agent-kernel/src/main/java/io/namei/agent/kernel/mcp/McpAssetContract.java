package io.namei.agent.kernel.mcp;

import java.util.regex.Pattern;

/** Contract constants and validation helpers for metadata-only MCP assets. */
public final class McpAssetContract {
  public static final int CURRENT_VERSION = 1;
  static final Pattern SERVER_ID = Pattern.compile("[a-z][a-z0-9_-]{0,15}");
  static final Pattern LOCAL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private static final int MAX_PUBLIC_TEXT_CODE_POINTS = 512;

  private McpAssetContract() {}

  static McpAssetContractViolation violation() {
    return new McpAssetContractViolation(McpAssetStableCode.MCP_ASSET_CONTRACT_INVALID);
  }

  static void requirePublicText(String value, boolean required) {
    if (value == null) {
      if (!required) {
        return;
      }
      throw violation();
    }
    if ((required && value.isBlank())
        || !value.equals(value.strip())
        || value.codePointCount(0, value.length()) > MAX_PUBLIC_TEXT_CODE_POINTS
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw violation();
    }
  }
}
