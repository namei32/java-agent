package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryType;
import java.time.Instant;

public record MemoryWriteRequest(
    String requestId, MemoryType type, String content, int emotionalWeight, Instant happenedAt) {
  public MemoryWriteRequest {
    requestId = MemoryManagementRules.requestId(requestId);
    if (type == null) {
      throw new IllegalArgumentException("Memory Type 不能为空");
    }
    content = MemoryManagementRules.content(content);
    if (emotionalWeight < 0 || emotionalWeight > 10) {
      throw new IllegalArgumentException("Emotional Weight 必须在 0..10");
    }
  }

  @Override
  public String toString() {
    return "MemoryWriteRequest[type=" + type + ", emotionalWeight=" + emotionalWeight + "]";
  }
}
