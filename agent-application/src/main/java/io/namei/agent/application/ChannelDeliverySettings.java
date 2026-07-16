package io.namei.agent.application;

import java.time.Duration;

public record ChannelDeliverySettings(Duration lease, Duration maxRetryAfter) {
  private static final Duration MAX_DURATION = Duration.ofDays(365);

  public ChannelDeliverySettings {
    lease = positiveBounded(lease, "Delivery Lease");
    maxRetryAfter = positiveBounded(maxRetryAfter, "最大 Retry-After");
  }

  private static Duration positiveBounded(Duration value, String field) {
    if (value == null
        || value.isZero()
        || value.isNegative()
        || value.compareTo(MAX_DURATION) > 0) {
      throw new IllegalArgumentException(field + " 必须为有界正数");
    }
    return value;
  }
}
