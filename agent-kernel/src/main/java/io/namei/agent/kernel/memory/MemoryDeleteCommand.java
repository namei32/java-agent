package io.namei.agent.kernel.memory;

import java.time.Instant;
import java.util.Objects;

public record MemoryDeleteCommand(
    MemoryScope scope, String requestId, String itemId, String argumentHash, Instant requestedAt) {
  public MemoryDeleteCommand {
    Objects.requireNonNull(scope, "scope");
    requestId = MemoryValueRules.requestId(requestId);
    itemId = MemoryValueRules.itemId(itemId);
    argumentHash = MemoryValueRules.sha256(argumentHash, "Argument Hash");
    Objects.requireNonNull(requestedAt, "requestedAt");
  }

  @Override
  public String toString() {
    return "MemoryDeleteCommand[redacted]";
  }
}
