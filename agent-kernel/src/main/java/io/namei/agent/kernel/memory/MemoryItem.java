package io.namei.agent.kernel.memory;

import java.time.Instant;
import java.util.Objects;

public record MemoryItem(
    String id,
    MemoryScope scope,
    MemoryType type,
    String content,
    String contentHash,
    EmbeddingVector embedding,
    String embeddingModel,
    int reinforcement,
    int emotionalWeight,
    MemorySourceKind sourceKind,
    Instant happenedAt,
    long revision,
    Instant createdAt,
    Instant updatedAt,
    MemoryLifecycleState lifecycleState) {
  public MemoryItem {
    id = MemoryValueRules.itemId(id);
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(type, "type");
    content = MemoryValueRules.content(content);
    contentHash = MemoryValueRules.sha256(contentHash, "Content Hash");
    Objects.requireNonNull(embedding, "embedding");
    embeddingModel = MemoryValueRules.required(embeddingModel, "Embedding Model", 256);
    if (reinforcement < 1) {
      throw new IllegalArgumentException("Reinforcement 必须大于零");
    }
    if (emotionalWeight < 0 || emotionalWeight > 10) {
      throw new IllegalArgumentException("Emotional Weight 必须在 0..10");
    }
    Objects.requireNonNull(sourceKind, "sourceKind");
    if (revision < 1) {
      throw new IllegalArgumentException("Revision 必须大于零");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(lifecycleState, "lifecycleState");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("Updated At 不能早于 Created At");
    }
  }

  public MemoryItem(
      String id,
      MemoryScope scope,
      MemoryType type,
      String content,
      String contentHash,
      EmbeddingVector embedding,
      String embeddingModel,
      int reinforcement,
      int emotionalWeight,
      MemorySourceKind sourceKind,
      Instant happenedAt,
      long revision,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        scope,
        type,
        content,
        contentHash,
        embedding,
        embeddingModel,
        reinforcement,
        emotionalWeight,
        sourceKind,
        happenedAt,
        revision,
        createdAt,
        updatedAt,
        MemoryLifecycleState.ACTIVE);
  }

  public int embeddingDimensions() {
    return embedding.dimensions();
  }

  @Override
  public String toString() {
    return "MemoryItem[type="
        + type
        + ", reinforcement="
        + reinforcement
        + ", revision="
        + revision
        + ", lifecycleState="
        + lifecycleState
        + "]";
  }
}
