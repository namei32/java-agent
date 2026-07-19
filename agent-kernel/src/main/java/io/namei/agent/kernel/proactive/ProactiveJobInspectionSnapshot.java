package io.namei.agent.kernel.proactive;

import java.util.Objects;

/**
 * Hash-safe, active-only projection of a local proactive job for read-only inspection.
 *
 * <p>It deliberately omits target hash, idempotency key, lease, owner and revision data.
 */
public record ProactiveJobInspectionSnapshot(
    ProactiveJobRef jobRef,
    ProactiveSchedule schedule,
    ProactiveJobState state,
    int attempts,
    int maxAttempts) {
  public ProactiveJobInspectionSnapshot {
    jobRef = Objects.requireNonNull(jobRef, "jobRef");
    schedule = Objects.requireNonNull(schedule, "schedule");
    state = Objects.requireNonNull(state, "state");
    if (state.terminal() || maxAttempts < 1 || maxAttempts > 8 || attempts < 0) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
    if (attempts > maxAttempts) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_BUDGET_EXHAUSTED);
    }
  }
}
