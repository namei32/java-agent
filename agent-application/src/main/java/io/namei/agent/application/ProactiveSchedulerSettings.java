package io.namei.agent.application;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 主动任务调度器的租约、等待和关闭边界。
 *
 * @param ownerId 当前进程竞争任务租约时使用的稳定所有者标识
 * @param leaseDuration 单次任务租约有效期
 * @param idleWait 没有可运行任务时的最长等待时间
 * @param recoveryBatchSize 单轮扫描的恢复任务上限
 * @param shutdownTimeout 停止调度后等待活动任务结束的期限
 */
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
