package io.namei.agent.kernel.control;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Server-issued, opaque, detail reference. Actor/Scope/TTL binding is enforced outside Kernel. */
public final class HistoryDetailRef {
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");

  private final String value;

  private HistoryDetailRef(String value) {
    this.value = value;
  }

  public static HistoryDetailRef fromBytes(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length != ControlPlaneContract.TURN_REFERENCE_BYTES) {
      throw new IllegalArgumentException("控制历史详情引用必须来自 128-bit 随机值");
    }
    return new HistoryDetailRef(
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.clone()));
  }

  public static HistoryDetailRef parse(String value) {
    return new HistoryDetailRef(requireCanonical(value, "控制历史详情引用格式无效"));
  }

  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof HistoryDetailRef that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return "HistoryDetailRef[value=<redacted>]";
  }

  static String requireCanonical(String value, String message) {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException(message);
    }
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(message);
    }
    if (decoded.length != ControlPlaneContract.TURN_REFERENCE_BYTES
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
      Arrays.fill(decoded, (byte) 0);
      throw new IllegalArgumentException(message);
    }
    Arrays.fill(decoded, (byte) 0);
    return value;
  }
}
