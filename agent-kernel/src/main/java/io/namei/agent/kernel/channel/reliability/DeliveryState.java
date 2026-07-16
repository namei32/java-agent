package io.namei.agent.kernel.channel.reliability;

public enum DeliveryState {
  PENDING,
  DELIVERING,
  DELIVERED,
  FAILED,
  UNKNOWN
}
