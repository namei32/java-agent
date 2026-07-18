package io.namei.agent.kernel.proactive;

public enum ProactiveStableCode {
  PROACTIVE_CONTRACT_INVALID(false),
  PROACTIVE_DISABLED(false),
  PROACTIVE_BUDGET_EXHAUSTED(false),
  PROACTIVE_COOLDOWN(false),
  PROACTIVE_TARGET_BUSY(true),
  PROACTIVE_DUPLICATE(false),
  PROACTIVE_LEASE_LOST(true),
  PROACTIVE_TIMEOUT(true),
  DRIFT_READ_ONLY(false),
  SUBAGENT_BUDGET_EXHAUSTED(false),
  SUBAGENT_CANCELLED(true);

  private final boolean retryable;

  ProactiveStableCode(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }

  public static ProactiveStableCode parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ProactiveContract.violation(PROACTIVE_CONTRACT_INVALID);
    }
  }
}
