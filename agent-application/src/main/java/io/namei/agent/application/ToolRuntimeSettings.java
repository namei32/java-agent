package io.namei.agent.application;

import java.time.Duration;
import java.util.Objects;

public record ToolRuntimeSettings(
    ToolRuntimeMode mode,
    int maxCallsPerResponse,
    int maxCallsPerTurn,
    Duration timeout,
    int maxConcurrentCalls,
    int maxResultCharacters) {
  public ToolRuntimeSettings {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(timeout, "timeout");
    if (maxCallsPerResponse < 1
        || maxCallsPerTurn < 1
        || maxConcurrentCalls < 1
        || maxResultCharacters < 1
        || timeout.isZero()
        || timeout.isNegative()) {
      throw new IllegalArgumentException("Tool Runtime 预算必须大于零");
    }
    if (maxCallsPerTurn < maxCallsPerResponse) {
      throw new IllegalArgumentException("单轮 Tool Call 上限不能小于单响应上限");
    }
  }

  public static ToolRuntimeSettings readOnlyDefaults() {
    return new ToolRuntimeSettings(
        ToolRuntimeMode.READ_ONLY, 8, 16, Duration.ofSeconds(5), 32, 20_000);
  }

  public static ToolRuntimeSettings disabled() {
    var defaults = readOnlyDefaults();
    return new ToolRuntimeSettings(
        ToolRuntimeMode.DISABLED,
        defaults.maxCallsPerResponse(),
        defaults.maxCallsPerTurn(),
        defaults.timeout(),
        defaults.maxConcurrentCalls(),
        defaults.maxResultCharacters());
  }
}
