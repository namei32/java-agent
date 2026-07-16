package io.namei.agent.application;

import java.time.Instant;
import java.util.Objects;

public record ChannelDeliveryStep(Status status, Instant retryAt) {
  public ChannelDeliveryStep {
    Objects.requireNonNull(status, "status");
    if ((status == Status.RETRY_SCHEDULED) != (retryAt != null)) {
      throw new IllegalArgumentException("Retry Step 必须且只能携带 retryAt");
    }
  }

  public static ChannelDeliveryStep empty() {
    return new ChannelDeliveryStep(Status.EMPTY, null);
  }

  public static ChannelDeliveryStep partDelivered() {
    return new ChannelDeliveryStep(Status.PART_DELIVERED, null);
  }

  public static ChannelDeliveryStep delivered() {
    return new ChannelDeliveryStep(Status.DELIVERED, null);
  }

  public static ChannelDeliveryStep retryScheduled(Instant retryAt) {
    return new ChannelDeliveryStep(Status.RETRY_SCHEDULED, Objects.requireNonNull(retryAt));
  }

  public static ChannelDeliveryStep failed() {
    return new ChannelDeliveryStep(Status.FAILED, null);
  }

  public static ChannelDeliveryStep unknown() {
    return new ChannelDeliveryStep(Status.UNKNOWN, null);
  }

  public enum Status {
    EMPTY,
    PART_DELIVERED,
    DELIVERED,
    RETRY_SCHEDULED,
    FAILED,
    UNKNOWN
  }
}
