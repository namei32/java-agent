package io.namei.agent.bootstrap.config;

import io.namei.agent.kernel.memory.MemoryRecallMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 有界 Java Native 记忆召回 Tool 的严格、默认禁用激活配置。 */
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
