package io.namei.agent.kernel.memory;

import java.util.Objects;

public record MemoryWriteResult(MemoryWriteStatus status, MemoryItem item) {
  public MemoryWriteResult {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(item, "item");
  }
}
