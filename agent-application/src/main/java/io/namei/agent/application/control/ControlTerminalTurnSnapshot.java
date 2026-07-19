package io.namei.agent.application.control;

import io.namei.agent.kernel.control.ControlTerminalKind;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** A bounded, in-memory terminal Turn projection with no message or session data. */
public record ControlTerminalTurnSnapshot(
    ControlTurnRef turnRef, String channel, ControlTerminalKind terminalKind, Instant completedAt) {
  private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

  public ControlTerminalTurnSnapshot {
    turnRef = Objects.requireNonNull(turnRef, "turnRef");
    if (channel == null || !CHANNEL.matcher(channel).matches()) {
      throw new IllegalArgumentException("控制面 Channel 无效");
    }
    terminalKind = Objects.requireNonNull(terminalKind, "terminalKind");
    completedAt = Objects.requireNonNull(completedAt, "completedAt");
  }

  @Override
  public String toString() {
    return "ControlTerminalTurnSnapshot[turnRef=<redacted>, channel="
        + channel
        + ", terminalKind="
        + terminalKind
        + ", completedAt="
        + completedAt
        + "]";
  }
}
