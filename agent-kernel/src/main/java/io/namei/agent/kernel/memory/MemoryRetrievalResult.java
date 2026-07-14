package io.namei.agent.kernel.memory;

import java.util.Objects;

public record MemoryRetrievalResult(String block, MemoryRetrievalTrace trace) {
  public MemoryRetrievalResult {
    block = Objects.requireNonNull(block, "block").strip();
    Objects.requireNonNull(trace, "trace");
    boolean retrieved = trace.status() == MemoryRetrievalStatus.RETRIEVED;
    if (retrieved == block.isBlank()) {
      throw new IllegalArgumentException("检索状态与注入块不一致");
    }
  }

  public static MemoryRetrievalResult disabled() {
    return new MemoryRetrievalResult(
        "", new MemoryRetrievalTrace(MemoryRetrievalStatus.DISABLED, 0));
  }

  public static MemoryRetrievalResult empty() {
    return new MemoryRetrievalResult("", new MemoryRetrievalTrace(MemoryRetrievalStatus.EMPTY, 0));
  }

  public static MemoryRetrievalResult retrieved(String block, int injectedCount) {
    return new MemoryRetrievalResult(
        block, new MemoryRetrievalTrace(MemoryRetrievalStatus.RETRIEVED, injectedCount));
  }
}
