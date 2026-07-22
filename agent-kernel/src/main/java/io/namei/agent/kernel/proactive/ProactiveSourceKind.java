package io.namei.agent.kernel.proactive;

/** R14 P0 有意仅表示注入的本地 Fixture，绝不表示网络 Source。 */
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
