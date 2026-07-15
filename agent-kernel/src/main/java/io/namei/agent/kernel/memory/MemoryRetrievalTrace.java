package io.namei.agent.kernel.memory;

import java.util.Objects;

public record MemoryRetrievalTrace(MemoryRetrievalStatus status, int injectedCount) {
  public MemoryRetrievalTrace {
    Objects.requireNonNull(status, "status");
    if (injectedCount < 0) {
      throw new IllegalArgumentException("检索注入数量不能为负数");
    }
    if (status != MemoryRetrievalStatus.RETRIEVED && injectedCount != 0) {
      throw new IllegalArgumentException("非检索状态不能包含注入数量");
    }
  }
}
