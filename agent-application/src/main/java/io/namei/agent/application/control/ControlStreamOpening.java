package io.namei.agent.application.control;

import io.namei.agent.kernel.control.ControlTurnRef;
import io.namei.agent.kernel.control.ControlTurnState;
import java.time.Instant;
import java.util.Objects;

public record ControlStreamOpening(
    ControlTurnRef turnRef,
    ControlTurnState state,
    Long lastSequence,
    Instant subscribedAt,
    boolean replaySupported) {
  public ControlStreamOpening {
    turnRef = Objects.requireNonNull(turnRef, "turnRef");
    state = Objects.requireNonNull(state, "state");
    if (lastSequence != null && lastSequence < 0) {
      throw new IllegalArgumentException("控制面订阅最后序号不能为负数");
    }
    subscribedAt = Objects.requireNonNull(subscribedAt, "subscribedAt");
    if (replaySupported) {
      throw new IllegalArgumentException("V1 控制面不支持事件重放");
    }
  }

  @Override
  public String toString() {
    return "ControlStreamOpening[turnRef=<redacted>, state="
        + state
        + ", lastSequence="
        + lastSequence
        + ", subscribedAt="
        + subscribedAt
        + ", replaySupported="
        + replaySupported
        + "]";
  }
}
