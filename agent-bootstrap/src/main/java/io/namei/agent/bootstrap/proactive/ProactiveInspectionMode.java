package io.namei.agent.bootstrap.proactive;

/** 本地主动 Job 检视使用的严格、默认禁用激活模式。 */
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
