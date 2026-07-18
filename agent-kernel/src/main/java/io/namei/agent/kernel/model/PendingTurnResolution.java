package io.namei.agent.kernel.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned safe assistant projection that may complete a previously persisted pending turn.
 *
 * <p>This value deliberately contains neither Tool arguments nor an operation reference. Its text
 * is supplied only by a future capability that has already reduced a result to an approved
 * projection.
 */
public record PendingTurnResolution(
    String projectionVersion, ChatMessage safeAssistantProjection, OffsetDateTime resolvedAt) {
  private static final Pattern PROJECTION_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public PendingTurnResolution {
    projectionVersion = Objects.requireNonNull(projectionVersion, "projectionVersion").strip();
    if (!PROJECTION_VERSION.matcher(projectionVersion).matches()) {
      throw new IllegalArgumentException("Pending Turn Resolution 投影版本格式无效");
    }
    safeAssistantProjection =
        Objects.requireNonNull(safeAssistantProjection, "safeAssistantProjection");
    if (safeAssistantProjection.role() != MessageRole.ASSISTANT) {
      throw new IllegalArgumentException("Pending Turn Resolution 只能追加 Assistant 投影");
    }
    resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
  }

  @Override
  public String toString() {
    return "PendingTurnResolution[projectionVersion="
        + projectionVersion
        + ", safeAssistantProjection=<redacted>, resolvedAt="
        + resolvedAt
        + "]";
  }
}
