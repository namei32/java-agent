package io.namei.agent.kernel.memory;

import java.util.Objects;

public record MemoryDeleteResult(MemoryDeleteStatus status, String itemId) {
  public MemoryDeleteResult {
    Objects.requireNonNull(status, "status");
    itemId = MemoryValueRules.itemId(itemId);
  }

  @Override
  public String toString() {
    return "MemoryDeleteResult[status=" + status + "]";
  }
}
