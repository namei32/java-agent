package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlPlaneContract;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** The deliberately minimal, read-only R13-C1 control index projection. */
public record ControlIndexResponse(
    int schemaVersion,
    Instant observedAt,
    String state,
    String code,
    List<Channel> channels,
    List<Turn> turns,
    String nextCursor) {
  private static final Pattern STATE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern CODE = Pattern.compile("(?:|[A-Z][A-Z0-9_]{0,63})");
  private static final Pattern CURSOR = Pattern.compile("(?:|[A-Za-z0-9_-]{22})");

  public ControlIndexResponse {
    if (schemaVersion != ControlPlaneContract.CURRENT_VERSION) {
      throw new IllegalArgumentException("控制索引 schemaVersion 无效");
    }
    observedAt = Objects.requireNonNull(observedAt, "observedAt");
    if (state == null || !STATE.matcher(state).matches()) {
      throw new IllegalArgumentException("控制索引状态无效");
    }
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("控制索引状态码无效");
    }
    channels = List.copyOf(channels);
    turns = List.copyOf(turns);
    if (nextCursor == null || !CURSOR.matcher(nextCursor).matches()) {
      throw new IllegalArgumentException("控制索引游标无效");
    }
  }

  public record Channel(String channel, String state, int activeTurns, long unknownExecutionCount) {
    private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    public Channel {
      if (channel == null || !CHANNEL.matcher(channel).matches()) {
        throw new IllegalArgumentException("控制索引 Channel 无效");
      }
      if (state == null || !STATE.matcher(state).matches()) {
        throw new IllegalArgumentException("控制索引 Channel 状态无效");
      }
      if (activeTurns < 0 || unknownExecutionCount < 0) {
        throw new IllegalArgumentException("控制索引 Channel 计数不能为负数");
      }
    }
  }

  public record Turn(String turnRef, String channel, String state, Long lastSequence) {
    private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    public Turn {
      ControlTurnRef.parse(turnRef);
      if (channel == null || !CHANNEL.matcher(channel).matches()) {
        throw new IllegalArgumentException("控制索引 Turn Channel 无效");
      }
      if (state == null || !STATE.matcher(state).matches()) {
        throw new IllegalArgumentException("控制索引 Turn 状态无效");
      }
      if (lastSequence != null && lastSequence < 0) {
        throw new IllegalArgumentException("控制索引 Turn 序号不能为负数");
      }
    }
  }
}
