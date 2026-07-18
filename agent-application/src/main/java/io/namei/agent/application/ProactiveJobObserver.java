package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobState;

@FunctionalInterface
public interface ProactiveJobObserver {
  void onCommitted(ProactiveJobLease lease, ProactiveJobState terminalState);

  static ProactiveJobObserver noop() {
    return (lease, terminalState) -> {};
  }
}
