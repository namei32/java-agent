package io.namei.agent.kernel.channel;

public enum TurnFailureCode {
  INVALID_REQUEST(false),
  SESSION_BUSY(true),
  MODEL_TIMEOUT(true),
  MODEL_SAFETY_REJECTED(false),
  MODEL_CONTEXT_LIMIT(false),
  MODEL_UNAVAILABLE(true),
  INVALID_MODEL_RESPONSE(true),
  TURN_LIMIT_EXCEEDED(false),
  CONTEXT_UNAVAILABLE(true),
  APPROVAL_UNAVAILABLE(true),
  SIDE_EFFECT_STATE_UNKNOWN(false),
  PERSISTENCE_FAILED(false),
  INTERNAL_ERROR(false);

  private final boolean retryable;

  TurnFailureCode(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }

  static TurnFailureCode parse(String code) {
    for (TurnFailureCode candidate : values()) {
      if (candidate.name().equals(code)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("未知 Turn 失败码");
  }
}
