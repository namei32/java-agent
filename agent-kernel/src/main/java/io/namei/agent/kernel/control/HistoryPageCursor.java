package io.namei.agent.kernel.control;

import java.util.Base64;
import java.util.Objects;

/**
 * Server-issued opaque continuation cursor. Its binding and one-time consumption live in Bootstrap.
 */
public final class HistoryPageCursor {
  private final String value;

  private HistoryPageCursor(String value) {
    this.value = value;
  }

  public static HistoryPageCursor fromBytes(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length != ControlPlaneContract.TURN_REFERENCE_BYTES) {
      throw new IllegalArgumentException("控制历史详情游标必须来自 128-bit 随机值");
    }
    return new HistoryPageCursor(
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.clone()));
  }

  public static HistoryPageCursor parse(String value) {
    return new HistoryPageCursor(HistoryDetailRef.requireCanonical(value, "控制历史详情游标格式无效"));
  }

  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof HistoryPageCursor that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return "HistoryPageCursor[value=<redacted>]";
  }
}
