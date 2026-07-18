package io.namei.agent.bootstrap.control;

import java.time.Instant;
import java.util.Objects;

public record ControlAuditEvent(
    Instant observedAt,
    String requestId,
    String action,
    String result,
    String code,
    String actorHash,
    String turnHash,
    long count,
    long durationMillis) {
  public ControlAuditEvent {
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(result, "result");
    code = code == null ? "" : code;
    actorHash = actorHash == null ? "" : actorHash;
    turnHash = turnHash == null ? "" : turnHash;
    if (count < 0 || durationMillis < 0) {
      throw new IllegalArgumentException("控制面审计计数或耗时不能为负数");
    }
  }
}
