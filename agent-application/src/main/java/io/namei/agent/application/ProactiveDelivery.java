package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveJobLease;

@FunctionalInterface
public interface ProactiveDelivery {
  ProactiveDeliveryResult deliver(ProactiveJobLease lease);

  static ProactiveDelivery noop() {
    return ignored -> ProactiveDeliveryResult.NOT_DELIVERED;
  }
}
