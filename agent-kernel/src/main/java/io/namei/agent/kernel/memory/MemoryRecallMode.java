package io.namei.agent.kernel.memory;

import java.util.Locale;

/** Explicit activation modes for the bounded, read-only Java Native memory recall tool. */
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
