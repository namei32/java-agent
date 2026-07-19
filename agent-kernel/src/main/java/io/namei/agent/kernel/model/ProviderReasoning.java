package io.namei.agent.kernel.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A bounded, redacted provider reasoning segment that is valid only inside the current tool loop.
 */
public final class ProviderReasoning {
  public static final int MAX_CODE_POINTS = 16_384;

  private final String content;
  private final int codePointCount;

  private ProviderReasoning(String content, int codePointCount) {
    this.content = content;
    this.codePointCount = codePointCount;
  }

  public static Optional<ProviderReasoning> from(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String normalized = raw.strip();
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    int codePoints = normalized.codePointCount(0, normalized.length());
    if (codePoints > MAX_CODE_POINTS) {
      return Optional.empty();
    }
    return Optional.of(new ProviderReasoning(normalized, codePoints));
  }

  public String content() {
    return content;
  }

  public int codePointCount() {
    return codePointCount;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ProviderReasoning reasoning && content.equals(reasoning.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(content);
  }

  @Override
  public String toString() {
    return "ProviderReasoning[redacted, codePointCount=" + codePointCount + "]";
  }
}
