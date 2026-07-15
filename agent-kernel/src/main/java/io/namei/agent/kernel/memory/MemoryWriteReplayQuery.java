package io.namei.agent.kernel.memory;

import java.util.Objects;

public record MemoryWriteReplayQuery(MemoryMutationKey key, String argumentHash) {
  public MemoryWriteReplayQuery {
    Objects.requireNonNull(key, "key");
    argumentHash = MemoryValueRules.sha256(argumentHash, "Argument Hash");
  }

  @Override
  public String toString() {
    return "MemoryWriteReplayQuery[redacted]";
  }
}
