package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobLease;

@FunctionalInterface
public interface ProactivePlanner {
  ProactiveDecision plan(ProactiveJobLease lease, TurnCancellation cancellation);
}
