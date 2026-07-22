package io.namei.agent.application;

import java.util.Arrays;

/** 狭窄本地 R10-P3 上下文超限恢复路径的显式 Runtime 模式。 */
public enum ContextLimitRecoveryMode {
  DISABLED,
  SAFE_LOCAL;

  public static ContextLimitRecoveryMode parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("agent.context-limit-recovery.mode 必填");
    }
    return Arrays.stream(values())
        .filter(candidate -> candidate.name().equals(value))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("agent.context-limit-recovery.mode 必须是严格大写枚举"));
  }
}
