package io.namei.agent.kernel.proactive;

import java.util.Objects;

/**
 * The complete durable-state transition table. Lease recovery returns an uncommitted claim to
 * {@link ProactiveJobState#SCHEDULED}; it never reopens a terminal job.
 */
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
