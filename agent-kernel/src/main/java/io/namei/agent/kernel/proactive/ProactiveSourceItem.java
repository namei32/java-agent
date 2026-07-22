package io.namei.agent.kernel.proactive;

import java.util.Objects;
import java.util.regex.Pattern;

/** 供未来 Proactive Pipeline 使用的有界本地测试投影。它不是 URL、Fetch 请求、MCP Payload 或 Runtime 信任的指令。 */
public record ProactiveSourceItem(ProactiveSourceKind kind, String sourceRef, String safeText) {
  public static final int MAX_SAFE_TEXT_CODE_POINTS = 4_000;

  private static final Pattern REFERENCE = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final Pattern URL = Pattern.compile("(?i)https?://");

  public ProactiveSourceItem {
    kind = Objects.requireNonNull(kind, "kind");
    if (kind != ProactiveSourceKind.FIXED_LOCAL
        || sourceRef == null
        || !REFERENCE.matcher(sourceRef).matches()
        || safeText == null
        || safeText.isBlank()
        || safeText.codePointCount(0, safeText.length()) > MAX_SAFE_TEXT_CODE_POINTS
        || hasForbiddenControl(safeText)
        || URL.matcher(safeText).find()) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_SOURCE_INVALID);
    }
  }

  public static ProactiveSourceItem fixedLocal(
      ProactiveSourceKind kind, String sourceRef, String text) {
    if (text == null) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_SOURCE_INVALID);
    }
    return new ProactiveSourceItem(kind, sourceRef, text.replace("\r\n", "\n").replace('\r', '\n'));
  }

  private static boolean hasForbiddenControl(String value) {
    return value.codePoints().anyMatch(codePoint -> codePoint < 0x20 && codePoint != '\n');
  }
}
