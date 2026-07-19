package io.namei.agent.bootstrap.config;

import java.util.Objects;

/** Explicit activation boundary for the one approved local memory-forget capability. */
public enum MemoryForgetCapabilityMode {
  DISABLED,
  LOOPBACK_APPROVAL;

  static MemoryForgetCapabilityMode parse(String value) {
    Objects.requireNonNull(value, "agent.capabilities.memory-forget.mode");
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("agent.capabilities.memory-forget.mode 无效");
    }
  }
}
