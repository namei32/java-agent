package io.namei.agent.bootstrap.config;

import io.namei.agent.kernel.memory.MemoryRecallMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strict, default-disabled activation for the bounded Java Native memory recall Tool. */
@ConfigurationProperties("agent.memory-recall")
public record MemoryRecallProperties(String mode) {
  public MemoryRecallProperties {
    mode = mode == null ? MemoryRecallMode.DISABLED.name() : mode;
    MemoryRecallMode.parse(mode);
  }

  MemoryRecallMode toMode() {
    return MemoryRecallMode.parse(mode);
  }

  @Override
  public String toString() {
    return "MemoryRecallProperties[mode=" + mode + "]";
  }
}
