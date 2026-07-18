package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobLease;

@FunctionalInterface
public interface ProactiveGate {
  ProactiveDecision evaluate(ProactiveJobLease lease);
}
