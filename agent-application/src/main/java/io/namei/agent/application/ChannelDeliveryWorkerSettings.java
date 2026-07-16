package io.namei.agent.application;

import java.time.Duration;

public record ChannelDeliveryWorkerSettings(
    int batchSize, Duration idleWait, Duration shutdownTimeout) {
  private static final Duration MAX_DURATION = Duration.ofDays(365);

  public ChannelDeliveryWorkerSettings {
    if (batchSize < 1 || batchSize > 1_000) {
      throw new IllegalArgumentException("Delivery Worker Batch Size 必须在 1..1000");
    }
    idleWait = positiveBounded(idleWait, "Delivery Worker 空闲等待");
    shutdownTimeout = positiveBounded(shutdownTimeout, "Delivery Worker 关闭期限");
  }

  private static Duration positiveBounded(Duration value, String field) {
    if (value == null
        || value.isZero()
        || value.isNegative()
        || value.compareTo(MAX_DURATION) > 0) {
      throw new IllegalArgumentException(field + "必须为有界正数");
    }
    return value;
  }
}
