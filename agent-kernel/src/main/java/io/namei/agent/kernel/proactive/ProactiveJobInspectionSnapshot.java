package io.namei.agent.kernel.proactive;

import java.util.Objects;

/**
 * 供只读检视使用的本地 Proactive Job Hash 安全、仅 Active 投影。
 *
 * <p>它有意省略目标 Hash、幂等键、Lease、Owner 和 Revision 数据。
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
