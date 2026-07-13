package io.namei.agent.application;

import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import java.util.Objects;

final class LifecyclePublisher {
  private final TurnLifecycleObserver observer;

  LifecyclePublisher(TurnLifecycleObserver observer) {
    this.observer = Objects.requireNonNull(observer, "observer");
  }

  void emit(TurnLifecycleEvent event) {
    try {
      observer.onEvent(event);
    } catch (RuntimeException ignored) {
      // 可观测性故障不能改变业务结果。
    }
  }
}
