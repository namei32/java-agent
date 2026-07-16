package io.namei.agent.kernel.channel.reliability;

import java.util.Objects;

public final class ChannelReliabilityRules {
  private ChannelReliabilityRules() {}

  public static boolean canTransition(TurnClaimState from, TurnClaimState to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    return switch (from) {
      case RESERVED -> to == TurnClaimState.RUNNING || to == TurnClaimState.START_RETRYABLE;
      case START_RETRYABLE ->
          to == TurnClaimState.RESERVED || to == TurnClaimState.EXECUTION_UNKNOWN;
      case RUNNING ->
          to == TurnClaimState.TERMINAL_RECORDED || to == TurnClaimState.EXECUTION_UNKNOWN;
      case TERMINAL_RECORDED, EXECUTION_UNKNOWN -> false;
    };
  }

  public static boolean canTransition(DeliveryState from, DeliveryState to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    return switch (from) {
      case PENDING -> to == DeliveryState.DELIVERING;
      case DELIVERING ->
          to == DeliveryState.PENDING
              || to == DeliveryState.DELIVERED
              || to == DeliveryState.FAILED
              || to == DeliveryState.UNKNOWN;
      case DELIVERED, FAILED, UNKNOWN -> false;
    };
  }

  public static boolean canTransition(DeliveryPartState from, DeliveryPartState to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    return switch (from) {
      case PENDING, RETRY_WAIT -> to == DeliveryPartState.IN_FLIGHT;
      case IN_FLIGHT ->
          to == DeliveryPartState.RETRY_WAIT
              || to == DeliveryPartState.DELIVERED
              || to == DeliveryPartState.FAILED
              || to == DeliveryPartState.UNKNOWN;
      case DELIVERED, FAILED, UNKNOWN -> false;
    };
  }

  public static boolean canTransition(DeliveryAttemptOutcome from, DeliveryAttemptOutcome to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    return from == DeliveryAttemptOutcome.STARTED
        && (to == DeliveryAttemptOutcome.SUCCEEDED
            || to == DeliveryAttemptOutcome.RETRYABLE_REJECTED
            || to == DeliveryAttemptOutcome.PERMANENT_REJECTED
            || to == DeliveryAttemptOutcome.UNKNOWN);
  }
}
