package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryWriteStatus;
import java.util.Objects;

public record MemoryWriteOutcome(MemoryWriteStatus status, MemoryView memory) {
  public MemoryWriteOutcome {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(memory, "memory");
  }
}
