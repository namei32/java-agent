package io.namei.agent.kernel.channel.reliability;

public enum DeliveryPartState {
  PENDING,
  IN_FLIGHT,
  RETRY_WAIT,
  DELIVERED,
  FAILED,
  UNKNOWN
}
