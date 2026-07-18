package io.namei.agent.application.control;

import io.namei.agent.kernel.control.ControlTurnRef;
import io.namei.agent.kernel.control.ControlTurnState;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record ActiveTurnSnapshot(
    ControlTurnRef turnRef,
    String channel,
    ControlTurnState state,
    Instant startedAt,
    Long lastSequence,
    int subscriberCount) {
  private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

  public ActiveTurnSnapshot {
    turnRef = Objects.requireNonNull(turnRef, "turnRef");
    if (channel == null || !CHANNEL.matcher(channel).matches()) {
      throw new IllegalArgumentException("控制面 Channel 无效");
    }
    state = Objects.requireNonNull(state, "state");
    startedAt = Objects.requireNonNull(startedAt, "startedAt");
    if (lastSequence != null && lastSequence < 0) {
      throw new IllegalArgumentException("控制面最后序号不能为负数");
    }
    if (subscriberCount < 0) {
      throw new IllegalArgumentException("控制面订阅数不能为负数");
    }
  }

  @Override
  public String toString() {
    return "ActiveTurnSnapshot[turnRef=<redacted>, channel="
        + channel
        + ", state="
        + state
        + ", startedAt="
        + startedAt
        + ", lastSequence="
        + lastSequence
        + ", subscriberCount="
        + subscriberCount
        + "]";
  }
}
