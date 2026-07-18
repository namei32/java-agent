package io.namei.agent.kernel.proactive;

import java.util.Objects;
import java.util.regex.Pattern;

public record ScheduledJob(
    ProactiveJobRef jobRef,
    ProactiveSchedule schedule,
    String targetHash,
    String idempotencyKey,
    ProactiveJobState state,
    int attempts,
    int maxAttempts) {
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public ScheduledJob {
    jobRef = Objects.requireNonNull(jobRef, "jobRef");
    schedule = Objects.requireNonNull(schedule, "schedule");
    state = Objects.requireNonNull(state, "state");
    if (targetHash == null
        || idempotencyKey == null
        || !HASH.matcher(targetHash).matches()
        || !HASH.matcher(idempotencyKey).matches()) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
    if (maxAttempts < 1 || maxAttempts > 8 || attempts < 0) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
    if (attempts > maxAttempts) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_BUDGET_EXHAUSTED);
    }
  }
}
