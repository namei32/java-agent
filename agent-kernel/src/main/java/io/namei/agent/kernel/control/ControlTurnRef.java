package io.namei.agent.kernel.control;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ControlTurnRef {
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");

  private final String value;

  private ControlTurnRef(String value) {
    this.value = value;
  }

  public static ControlTurnRef fromBytes(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length != ControlPlaneContract.TURN_REFERENCE_BYTES) {
      throw new IllegalArgumentException("控制面 Turn 引用必须来自 128-bit 随机值");
    }
    return new ControlTurnRef(
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.clone()));
  }

  public static ControlTurnRef parse(String value) {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("控制面 Turn 引用格式无效");
    }
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("控制面 Turn 引用格式无效");
    }
    if (decoded.length != ControlPlaneContract.TURN_REFERENCE_BYTES
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
      Arrays.fill(decoded, (byte) 0);
      throw new IllegalArgumentException("控制面 Turn 引用格式无效");
    }
    Arrays.fill(decoded, (byte) 0);
    return new ControlTurnRef(value);
  }

  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof ControlTurnRef that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return "ControlTurnRef[value=<redacted>]";
  }
}
