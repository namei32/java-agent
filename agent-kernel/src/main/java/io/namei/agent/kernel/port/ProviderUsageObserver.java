package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.ProviderTurnUsage;

/** 单个已提交 Turn 匿名聚合数据的内部尽力型 Observer。 */
@FunctionalInterface
public interface ProviderUsageObserver {
  void onCommittedTurn(ProviderTurnUsage usage);

  static ProviderUsageObserver disabled() {
    return ignored -> {};
  }
}
