package io.namei.agent.application;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record ProactiveSchedulerSettings(
    String ownerId,
    Duration leaseDuration,
    Duration idleWait,
    int recoveryBatchSize,
    Duration shutdownTimeout) {
  private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  public ProactiveSchedulerSettings {
    if (ownerId == null || !OWNER.matcher(ownerId).matches()) {
      throw new IllegalArgumentException("Proactive ownerId 非法");
    }
    leaseDuration = positiveBounded(leaseDuration, Duration.ofMinutes(5), "Proactive lease");
    idleWait = positiveBounded(idleWait, Duration.ofDays(1), "Proactive 空闲等待");
    shutdownTimeout = positiveBounded(shutdownTimeout, Duration.ofMinutes(5), "Proactive 关闭期限");
    if (recoveryBatchSize < 1 || recoveryBatchSize > 256) {
      throw new IllegalArgumentException("Proactive recovery batch 必须在 1..256");
    }
  }

  private static Duration positiveBounded(Duration value, Duration maximum, String field) {
    Objects.requireNonNull(value, field);
    if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(field + "必须为有界正数");
    }
    return value;
  }
}
