package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryDeleteStatus;
import java.util.Objects;

public record MemoryDeleteOutcome(MemoryDeleteStatus status, String itemId) {
  public MemoryDeleteOutcome {
    Objects.requireNonNull(status, "status");
    itemId = MemoryManagementRules.itemId(itemId);
  }

  @Override
  public String toString() {
    return "MemoryDeleteOutcome[status=" + status + "]";
  }
}
