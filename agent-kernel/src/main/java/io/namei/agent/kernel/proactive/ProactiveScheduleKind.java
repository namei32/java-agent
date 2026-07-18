package io.namei.agent.kernel.proactive;

public enum ProactiveScheduleKind {
  AT,
  EVERY;

  public static ProactiveScheduleKind parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_CONTRACT_INVALID);
    }
  }
}
