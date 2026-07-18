package io.namei.agent.kernel.proactive;

/** The R14 P0/P1 boundary has no durable automatic memory mutation. */
public enum ProactiveMemoryMutation {
  NONE;

  public static ProactiveMemoryMutation parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ProactiveContract.violation(ProactiveStableCode.MEMORY_MUTATION_FORBIDDEN);
    }
  }
}
