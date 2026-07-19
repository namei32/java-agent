package io.namei.agent.bootstrap.proactive;

/** Strict, default-disabled activation modes for local proactive job inspection. */
public enum ProactiveInspectionMode {
  DISABLED,
  ACTIVE_RUNTIME;

  public static ProactiveInspectionMode parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new IllegalArgumentException("agent.proactive-inspection.mode 无效", invalid);
    }
  }
}
