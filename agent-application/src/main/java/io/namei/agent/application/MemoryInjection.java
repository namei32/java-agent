package io.namei.agent.application;

import java.util.Objects;

public record MemoryInjection(String block, int rulesCount, int relatedCount) {
  public MemoryInjection {
    block = Objects.requireNonNull(block, "block").strip();
    if (rulesCount < 0 || relatedCount < 0) {
      throw new IllegalArgumentException("Memory Injection 数量不能为负数");
    }
    if ((rulesCount + relatedCount == 0) != block.isBlank()) {
      throw new IllegalArgumentException("Memory Injection 数量与正文不一致");
    }
  }

  public int injectedCount() {
    return Math.addExact(rulesCount, relatedCount);
  }

  @Override
  public String toString() {
    return "MemoryInjection[rulesCount=" + rulesCount + ", relatedCount=" + relatedCount + "]";
  }
}
