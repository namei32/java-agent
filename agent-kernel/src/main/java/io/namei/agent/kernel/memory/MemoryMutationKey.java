package io.namei.agent.kernel.memory;

import java.util.Objects;

public record MemoryMutationKey(MemoryScope scope, String requestId) {
  public MemoryMutationKey {
    Objects.requireNonNull(scope, "scope");
    requestId = MemoryValueRules.requestId(requestId);
  }

  @Override
  public String toString() {
    return "MemoryMutationKey[redacted]";
  }
}
