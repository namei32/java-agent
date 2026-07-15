package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryDeleteCommand;
import io.namei.agent.kernel.memory.MemoryDeleteResult;
import io.namei.agent.kernel.port.MemoryWritePort;
import java.time.Clock;
import java.util.Objects;

public final class MemoryDeleteService {
  private final MemoryWritePort writer;
  private final Clock clock;

  public MemoryDeleteService(MemoryWritePort writer, Clock clock) {
    this.writer = Objects.requireNonNull(writer, "writer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public MemoryDeleteOutcome delete(String sessionId, String requestId, String itemId) {
    var scope = MemoryManagementRules.scope(sessionId);
    String normalizedRequestId = MemoryManagementRules.requestId(requestId);
    String normalizedItemId = MemoryManagementRules.itemId(itemId);
    String argumentHash = MemoryManagementRules.deleteArgumentHash(normalizedItemId);
    var command =
        new MemoryDeleteCommand(
            scope, normalizedRequestId, normalizedItemId, argumentHash, clock.instant());
    MemoryDeleteResult result = Objects.requireNonNull(writer.delete(command), "delete result");
    return new MemoryDeleteOutcome(result.status(), result.itemId());
  }
}
