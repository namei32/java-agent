package io.namei.agent.kernel.proactive;

/** R14 P0 deliberately represents only an injected local fixture, never a network source. */
public enum ProactiveSourceKind {
  FIXED_LOCAL;

  public static ProactiveSourceKind parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ProactiveContract.violation(ProactiveStableCode.PROACTIVE_SOURCE_INVALID);
    }
  }
}
