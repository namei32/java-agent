package io.namei.agent.bootstrap.http;

import io.namei.agent.kernel.memory.MemoryDeleteStatus;
import java.util.Objects;

public record MemoryDeleteHttpResponse(MemoryDeleteStatus status, String id) {
  public MemoryDeleteHttpResponse {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(id, "id");
  }
}
