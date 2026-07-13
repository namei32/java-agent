package io.namei.agent.kernel.port;

import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;

@FunctionalInterface
public interface TurnLifecycleObserver {
  void onEvent(TurnLifecycleEvent event);

  static TurnLifecycleObserver noop() {
    return event -> {};
  }
}
