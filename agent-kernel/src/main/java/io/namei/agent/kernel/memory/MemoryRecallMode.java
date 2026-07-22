package io.namei.agent.kernel.memory;

import java.util.Locale;

/** 有界只读 Java Native 记忆召回 Tool 的显式激活模式。 */
public enum MemoryRecallMode {
  DISABLED,
  CURRENT_SCOPE_READ_ONLY;

  public static MemoryRecallMode parse(String value) {
    if (value == null || !value.equals(value.toUpperCase(Locale.ROOT))) {
      throw new IllegalArgumentException("agent.memory-recall.mode 无效");
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("agent.memory-recall.mode 无效", invalid);
    }
  }
}
