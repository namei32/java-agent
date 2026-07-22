package io.namei.agent.kernel.proactive;

/** R14 P0/P1 边界不具备持久自动 Memory 变更。 */
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
