package io.namei.agent.application.control;

import java.time.Instant;

@FunctionalInterface
public interface ActiveTurnObserver {
  ActiveTurnRegistration register(
      String channel, ControlCancellationHandle cancellation, Instant startedAt);

  static ActiveTurnObserver disabled() {
    return (channel, cancellation, startedAt) -> ActiveTurnRegistration.disabled();
  }
}
