package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveStableCode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** 有意排除 Prompt、模型正文、路径、Session 和原始目标值。 */
public record ProactiveAuditEvent(
    String targetHash, Action action, Optional<ProactiveStableCode> code, Instant occurredAt) {
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public enum Action {
    GATE,
    SOURCE,
    DRIFT,
    PROJECTION,
    PLAN,
    DELIVERY,
    MEMORY,
    PEER
  }

  public ProactiveAuditEvent {
    if (targetHash == null || !HASH.matcher(targetHash).matches()) {
      throw new IllegalArgumentException("Proactive audit target hash 非法");
    }
    action = Objects.requireNonNull(action, "action");
    code = Objects.requireNonNull(code, "code");
    occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
