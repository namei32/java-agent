package io.namei.agent.kernel.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 可以完成先前已持久化 Pending Turn 的版本化安全 Assistant 投影。
 *
 * <p>该值有意不包含 Tool 参数或 Operation 引用。其正文仅由未来已经将结果缩减为获批投影的 Capability 提供。
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
