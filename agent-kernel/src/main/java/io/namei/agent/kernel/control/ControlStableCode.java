package io.namei.agent.kernel.control;

import java.util.Objects;

public enum ControlStableCode {
  CONTROL_REQUEST_INVALID(false),
  CONTROL_MODE_INVALID(false),
  CONTROL_BINDING_INVALID(false),
  CONTROL_REMOTE_ACCESS_REJECTED(false),
  CONTROL_HOST_REJECTED(false),
  CONTROL_ORIGIN_REJECTED(false),
  CONTROL_SESSION_CAPACITY_EXCEEDED(false),
  CONTROL_AUTHENTICATION_REQUIRED(false),
  CONTROL_SNAPSHOT_UNAVAILABLE(true),
  CONTROL_SHUTTING_DOWN(true),
  CONTROL_TURN_NOT_FOUND(false),
  CONTROL_TURN_ALREADY_TERMINAL(false),
  CONTROL_EVENT_REPLAY_UNAVAILABLE(false),
  CONTROL_SUBSCRIBER_CAPACITY_EXCEEDED(false),
  CONTROL_STREAM_LIFETIME_EXCEEDED(false),
  CONTROL_SLOW_CONSUMER(false),
  CONTROL_TURN_REGISTRY_SATURATED(false),
  CONTROL_SOURCE_ENDED(false),
  CONTROL_SHUTDOWN_TIMEOUT(false),
  PENDING_RECOVERY_REQUEST_INVALID(false),
  PENDING_RECOVERY_NOT_FOUND(false),
  PENDING_RECOVERY_NOT_RESUMABLE(false),
  PENDING_RECOVERY_UNKNOWN_REQUIRES_OPERATOR(false),
  PENDING_RECOVERY_NOT_CANCELLABLE(false),
  PENDING_RECOVERY_UNAVAILABLE(true);

  private final boolean retryable;

  ControlStableCode(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }

  public static ControlStableCode parse(String value) {
    Objects.requireNonNull(value, "value");
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("未知控制面稳定码");
    }
  }
}
