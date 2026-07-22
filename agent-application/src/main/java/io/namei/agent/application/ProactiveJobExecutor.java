package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobState;

/** 执行已领取的 Job；实现自身不得执行持久投递。 */
@FunctionalInterface
public interface ProactiveJobExecutor {
  ProactiveJobState execute(ProactiveJobLease lease, TurnCancellation cancellation);
}
