package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.ProviderTurnUsage;

/** Internal, best-effort observer for the anonymous aggregate of one committed turn. */
@FunctionalInterface
public interface ProviderUsageObserver {
  void onCommittedTurn(ProviderTurnUsage usage);

  static ProviderUsageObserver disabled() {
    return ignored -> {};
  }
}
