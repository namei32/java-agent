package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryType;
import java.time.Instant;
import java.util.Objects;

public record MemoryView(
    String id,
    MemoryType type,
    String content,
    int reinforcement,
    int emotionalWeight,
    Instant happenedAt,
    Instant createdAt,
    Instant updatedAt) {
  public MemoryView {
    id = MemoryManagementRules.itemId(id);
    Objects.requireNonNull(type, "type");
    content = MemoryManagementRules.content(content);
    if (reinforcement < 1) {
      throw new IllegalArgumentException("Reinforcement 必须大于零");
    }
    if (emotionalWeight < 0 || emotionalWeight > 10) {
      throw new IllegalArgumentException("Emotional Weight 必须在 0..10");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  static MemoryView from(MemoryItem item) {
    Objects.requireNonNull(item, "item");
    return new MemoryView(
        item.id(),
        item.type(),
        item.content(),
        item.reinforcement(),
        item.emotionalWeight(),
        item.happenedAt(),
        item.createdAt(),
        item.updatedAt());
  }

  @Override
  public String toString() {
    return "MemoryView[type="
        + type
        + ", reinforcement="
        + reinforcement
        + ", emotionalWeight="
        + emotionalWeight
        + "]";
  }
}
