package io.namei.agent.kernel.control;

import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque trusted scope binding; it never carries a Session, route, or actor value. */
public final class HistoryScopeCapability {
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

  private final String binding;

  private HistoryScopeCapability(String binding) {
    this.binding = binding;
  }

  /**
   * Creates a capability only from a precomputed trusted SHA-256 digest.
   *
   * <p>The caller is responsible for keeping the source Session mapping outside the Kernel.
   */
  public static HistoryScopeCapability fromTrustedDigest(String binding) {
    if (binding == null || !DIGEST.matcher(binding).matches()) {
      throw new IllegalArgumentException("控制历史 Scope Capability 格式无效");
    }
    return new HistoryScopeCapability(binding);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof HistoryScopeCapability that && binding.equals(that.binding);
  }

  @Override
  public int hashCode() {
    return Objects.hash(binding);
  }

  @Override
  public String toString() {
    return "HistoryScopeCapability[redacted]";
  }
}
