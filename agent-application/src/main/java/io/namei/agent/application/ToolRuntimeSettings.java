package io.namei.agent.application;

import java.time.Duration;
import java.util.Objects;

/**
 * 单次 Agent Turn 的工具执行预算和并发边界。
 *
 * @param mode 工具总体运行模式
 * @param maxCallsPerResponse 单个模型响应允许产生的最大 Tool Call 数
 * @param maxCallsPerTurn 整个 Turn 累计允许的最大 Tool Call 数
 * @param timeout 单次工具调用超时
 * @param maxConcurrentCalls 单批工具调用并发上限
 * @param maxResultCharacters 单个工具结果反馈给模型前的字符上限
 */
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
