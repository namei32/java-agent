package io.namei.agent.kernel.proactive;

public enum ProactiveJobState {
  SCHEDULED,
  CLAIMED,
  RUNNING,
  SUCCEEDED,
  SKIPPED,
  FAILED,
  CANCELLED;

  public boolean terminal() {
    return this == SUCCEEDED || this == SKIPPED || this == FAILED || this == CANCELLED;
  }

  public static ProactiveJobState parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
  }
}
