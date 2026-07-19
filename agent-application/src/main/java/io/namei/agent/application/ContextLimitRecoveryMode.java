package io.namei.agent.application;

import java.util.Arrays;

/** Explicit runtime mode for the narrow R10-P3 local context-limit recovery path. */
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
