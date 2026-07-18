package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobState;

/** Executes a claimed job. Implementations must not perform durable delivery themselves. */
@FunctionalInterface
public interface ProactiveJobExecutor {
  ProactiveJobState execute(ProactiveJobLease lease, TurnCancellation cancellation);
}
