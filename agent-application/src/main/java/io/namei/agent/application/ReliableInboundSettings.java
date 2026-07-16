package io.namei.agent.application;

import java.time.Duration;
import java.util.List;

public record ReliableInboundSettings(
    int maxConcurrentTurns,
    Duration turnLease,
    int recoveryBatchSize,
    String chunkAlgorithm,
    List<String> sessionBusyParts,
    List<String> noActiveTurnParts) {
  public ReliableInboundSettings {
    if (maxConcurrentTurns < 1 || maxConcurrentTurns > 1_000) {
      throw new IllegalArgumentException("可靠渠道并发 Turn 数必须在 1..1000");
    }
    if (turnLease == null || turnLease.isZero() || turnLease.isNegative()) {
      throw new IllegalArgumentException("可靠渠道 Turn Lease 必须为正数");
    }
    if (recoveryBatchSize < 1 || recoveryBatchSize > 1_000) {
      throw new IllegalArgumentException("可靠渠道恢复批次必须在 1..1000");
    }
    chunkAlgorithm = requireText(chunkAlgorithm, "分片算法");
    sessionBusyParts = requireParts(sessionBusyParts, "Busy 固定反馈");
    noActiveTurnParts = requireParts(noActiveTurnParts, "无活动 Turn 固定反馈");
  }

  private static List<String> requireParts(List<String> values, String field) {
    if (values == null || values.isEmpty() || values.size() > 16) {
      throw new IllegalArgumentException(field + "分片数量无效");
    }
    List<String> copy = List.copyOf(values);
    if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException(field + "包含空分片");
    }
    return copy;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.strip();
  }

  @Override
  public String toString() {
    return "ReliableInboundSettings[maxConcurrentTurns="
        + maxConcurrentTurns
        + ", sensitiveFields=<redacted>]";
  }
}
