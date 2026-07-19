package io.namei.agent.kernel.memory;

import java.time.Instant;
import java.util.Objects;

public record MemoryMutation(
    MemoryMutationOperation operation,
    String argumentHash,
    String itemId,
    MemoryMutationStatus status,
    Instant createdAt) {
  public MemoryMutation {
    Objects.requireNonNull(operation, "operation");
    argumentHash = MemoryValueRules.sha256(argumentHash, "Argument Hash");
    itemId = MemoryValueRules.itemId(itemId);
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    boolean valid =
        switch (operation) {
          case UPSERT ->
              status == MemoryMutationStatus.CREATED || status == MemoryMutationStatus.REINFORCED;
          case DELETE ->
              status == MemoryMutationStatus.DELETED || status == MemoryMutationStatus.NOT_FOUND;
          case FORGET -> status == MemoryMutationStatus.FORGOTTEN;
        };
    if (!valid) {
      throw new IllegalArgumentException("Mutation Operation 与 Status 不一致");
    }
  }

  @Override
  public String toString() {
    return "MemoryMutation[operation=" + operation + ", status=" + status + "]";
  }
}
