package io.namei.agent.kernel.proactive;

import java.time.Duration;
import java.util.Objects;

/** Static P4 resource envelope; it is not a process, network, or file-system resource request. */
public record LocalFakePeerResourceBudget(
    int maxOutputCodePoints, Duration timeout, int maxConcurrentTasks) {
  public static final int FIXED_MAX_OUTPUT_CODE_POINTS = 1_024;
  public static final Duration FIXED_TIMEOUT = Duration.ofSeconds(5);
  public static final int FIXED_MAX_CONCURRENT_TASKS = 1;

  public LocalFakePeerResourceBudget {
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (maxOutputCodePoints != FIXED_MAX_OUTPUT_CODE_POINTS
        || !timeout.equals(FIXED_TIMEOUT)
        || maxConcurrentTasks != FIXED_MAX_CONCURRENT_TASKS) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }

  public static LocalFakePeerResourceBudget fixed() {
    return new LocalFakePeerResourceBudget(
        FIXED_MAX_OUTPUT_CODE_POINTS, FIXED_TIMEOUT, FIXED_MAX_CONCURRENT_TASKS);
  }

  @Override
  public String toString() {
    return "LocalFakePeerResourceBudget[maxOutputCodePoints="
        + maxOutputCodePoints
        + ", timeout="
        + timeout
        + ", maxConcurrentTasks="
        + maxConcurrentTasks
        + "]";
  }
}
