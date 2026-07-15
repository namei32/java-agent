package io.namei.agent.bootstrap.http;

import io.namei.agent.application.MemoryView;
import io.namei.agent.kernel.memory.MemoryWriteStatus;
import java.util.Objects;

public record MemoryWriteHttpResponse(MemoryWriteStatus status, MemoryView memory) {
  public MemoryWriteHttpResponse {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(memory, "memory");
  }
}
