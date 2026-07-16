package io.namei.agent.bootstrap.channel;

import java.util.Objects;
import java.util.regex.Pattern;

public record ChannelStatusSnapshot(
    String name,
    ChannelState state,
    String code,
    int activeTurns,
    int consecutiveFailures,
    ChannelReliabilityStatus reliability) {
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
  private static final Pattern CODE = Pattern.compile("(?:|[A-Z][A-Z0-9_]{0,63})");

  public ChannelStatusSnapshot {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Channel 名称不合法");
    }
    state = Objects.requireNonNull(state, "state");
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Channel 状态码不合法");
    }
    if (activeTurns < 0) {
      throw new IllegalArgumentException("Channel 活动 Turn 数不能为负数");
    }
    if (consecutiveFailures < 0) {
      throw new IllegalArgumentException("Channel 连续失败数不能为负数");
    }
    reliability = Objects.requireNonNull(reliability, "reliability");
  }

  public ChannelStatusSnapshot(
      String name, ChannelState state, String code, int activeTurns, int consecutiveFailures) {
    this(name, state, code, activeTurns, consecutiveFailures, ChannelReliabilityStatus.disabled());
  }

  public static ChannelStatusSnapshot failed(String name, String code) {
    return new ChannelStatusSnapshot(name, ChannelState.FAILED, code, 0, 1);
  }
}
