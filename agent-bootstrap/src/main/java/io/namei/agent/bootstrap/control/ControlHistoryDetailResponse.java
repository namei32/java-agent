package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlPlaneContract;
import io.namei.agent.kernel.control.ControlStableCode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Safe HTTP projection for an issued history detail reference or one zero-content page. */
public record ControlHistoryDetailResponse(
    int schemaVersion,
    Instant observedAt,
    String state,
    String code,
    String detailRef,
    List<Item> items,
    String nextCursor) {
  private static final Pattern OPAQUE = Pattern.compile("(?:|[A-Za-z0-9_-]{22})");

  public ControlHistoryDetailResponse {
    ControlPlaneContract.requireCurrentVersion(schemaVersion);
    observedAt = Objects.requireNonNull(observedAt, "observedAt");
    if (!List.of("REFERENCE_ISSUED", "READY", "DEGRADED", "SHUTTING_DOWN").contains(state)) {
      throw new IllegalArgumentException("控制历史详情状态无效");
    }
    code = code == null ? "" : code;
    if (detailRef == null || !OPAQUE.matcher(detailRef).matches()) {
      throw new IllegalArgumentException("控制历史详情引用投影无效");
    }
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (items.size() > 20) {
      throw new IllegalArgumentException("控制历史详情项目超过批准上限");
    }
    if (nextCursor == null || !OPAQUE.matcher(nextCursor).matches()) {
      throw new IllegalArgumentException("控制历史详情游标投影无效");
    }
    requireStateProjection(state, code, detailRef, items, nextCursor);
  }

  @Override
  public String toString() {
    return "ControlHistoryDetailResponse[schemaVersion="
        + schemaVersion
        + ", observedAt="
        + observedAt
        + ", state="
        + state
        + ", code="
        + code
        + ", detailRef=<redacted>, items="
        + items.size()
        + ", nextCursor=<redacted>]";
  }

  public record Item(String role, Instant occurredAt) {
    public Item {
      if (!"USER".equals(role) && !"ASSISTANT".equals(role)) {
        throw new IllegalArgumentException("控制历史详情角色投影无效");
      }
      occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  private static void requireStateProjection(
      String state, String code, String detailRef, List<Item> items, String nextCursor) {
    switch (state) {
      case "REFERENCE_ISSUED" -> {
        if (!code.isEmpty() || detailRef.isEmpty() || !items.isEmpty() || !nextCursor.isEmpty()) {
          throw new IllegalArgumentException("控制历史详情引用签发投影无效");
        }
      }
      case "READY" -> {
        if (!code.isEmpty() || !detailRef.isEmpty()) {
          throw new IllegalArgumentException("控制历史详情页面投影无效");
        }
      }
      case "DEGRADED" -> {
        if (!ControlStableCode.CONTROL_SNAPSHOT_UNAVAILABLE.name().equals(code)
            || !detailRef.isEmpty()
            || !items.isEmpty()
            || !nextCursor.isEmpty()) {
          throw new IllegalArgumentException("控制历史详情降级投影无效");
        }
      }
      case "SHUTTING_DOWN" -> {
        if (!ControlStableCode.CONTROL_SHUTTING_DOWN.name().equals(code)
            || !detailRef.isEmpty()
            || !items.isEmpty()
            || !nextCursor.isEmpty()) {
          throw new IllegalArgumentException("控制历史详情关闭投影无效");
        }
      }
      default -> throw new IllegalStateException("未验证的控制历史详情状态");
    }
  }
}
