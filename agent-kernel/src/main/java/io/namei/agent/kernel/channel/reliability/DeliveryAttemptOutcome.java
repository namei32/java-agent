package io.namei.agent.kernel.channel.reliability;

public enum DeliveryAttemptOutcome {
  STARTED,
  SUCCEEDED,
  RETRYABLE_REJECTED,
  PERMANENT_REJECTED,
  UNKNOWN
}
