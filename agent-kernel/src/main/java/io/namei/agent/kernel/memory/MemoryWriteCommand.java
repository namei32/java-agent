package io.namei.agent.kernel.memory;

import java.time.Instant;
import java.util.Objects;

public record MemoryWriteCommand(
    MemoryScope scope,
    String requestId,
    String itemId,
    MemoryType type,
    String content,
    String contentHash,
    EmbeddingVector embedding,
    String embeddingModel,
    int emotionalWeight,
    MemorySourceKind sourceKind,
    Instant happenedAt,
    String argumentHash,
    Instant requestedAt) {
  public MemoryWriteCommand {
    Objects.requireNonNull(scope, "scope");
    requestId = MemoryValueRules.requestId(requestId);
    itemId = MemoryValueRules.itemId(itemId);
    Objects.requireNonNull(type, "type");
    content = MemoryValueRules.content(content);
    contentHash = MemoryValueRules.sha256(contentHash, "Content Hash");
    Objects.requireNonNull(embedding, "embedding");
    embeddingModel = MemoryValueRules.required(embeddingModel, "Embedding Model", 256);
    if (emotionalWeight < 0 || emotionalWeight > 10) {
      throw new IllegalArgumentException("Emotional Weight 必须在 0..10");
    }
    Objects.requireNonNull(sourceKind, "sourceKind");
    argumentHash = MemoryValueRules.sha256(argumentHash, "Argument Hash");
    Objects.requireNonNull(requestedAt, "requestedAt");
  }

  @Override
  public String toString() {
    return "MemoryWriteCommand[type=" + type + ", emotionalWeight=" + emotionalWeight + "]";
  }
}
