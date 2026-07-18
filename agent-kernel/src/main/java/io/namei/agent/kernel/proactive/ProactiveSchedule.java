package io.namei.agent.kernel.proactive;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record ProactiveSchedule(ProactiveScheduleKind kind, Instant nextRunAt, Duration every) {
  public ProactiveSchedule {
    kind = Objects.requireNonNull(kind, "kind");
    nextRunAt = Objects.requireNonNull(nextRunAt, "nextRunAt");
    if (kind == ProactiveScheduleKind.AT && every != null) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
    if (kind == ProactiveScheduleKind.EVERY
        && (every == null
            || every.compareTo(Duration.ofSeconds(1)) < 0
            || every.compareTo(Duration.ofDays(365)) > 0)) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
  }
}
