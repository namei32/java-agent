package io.namei.agent.kernel.proactive;

import java.util.Objects;

/** 完整的持久状态转换表。Lease 恢复会将未提交 Claim 返回到 {@link ProactiveJobState#SCHEDULED}，绝不重新打开终态 Job。 */
public final class ProactiveJobTransition {
  private ProactiveJobTransition() {}

  public static boolean isAllowed(ProactiveJobState from, ProactiveJobState to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    return switch (from) {
      case SCHEDULED -> to == ProactiveJobState.CLAIMED || to == ProactiveJobState.CANCELLED;
      case CLAIMED ->
          to == ProactiveJobState.SCHEDULED
              || to == ProactiveJobState.RUNNING
              || to == ProactiveJobState.CANCELLED;
      case RUNNING ->
          to == ProactiveJobState.SCHEDULED
              || to == ProactiveJobState.SUCCEEDED
              || to == ProactiveJobState.SKIPPED
              || to == ProactiveJobState.FAILED
              || to == ProactiveJobState.CANCELLED;
      case SUCCEEDED, SKIPPED, FAILED, CANCELLED -> false;
    };
  }
}
