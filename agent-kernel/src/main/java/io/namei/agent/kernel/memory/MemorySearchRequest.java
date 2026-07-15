package io.namei.agent.kernel.memory;

import java.time.Instant;
import java.util.Objects;

public record MemorySearchRequest(
    MemoryScope scope,
    EmbeddingVector queryEmbedding,
    String embeddingModel,
    int topK,
    double scoreThreshold,
    double hotnessAlpha,
    double halfLifeDays,
    int maxCandidates,
    Instant requestedAt) {
  public MemorySearchRequest {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(queryEmbedding, "queryEmbedding");
    embeddingModel = MemoryValueRules.required(embeddingModel, "Embedding Model", 256);
    if (topK < 1 || topK > 100) {
      throw new IllegalArgumentException("Top K 必须在 1..100");
    }
    if (!Double.isFinite(scoreThreshold) || scoreThreshold < -1.0 || scoreThreshold > 1.0) {
      throw new IllegalArgumentException("Score Threshold 必须在 -1..1");
    }
    if (!Double.isFinite(hotnessAlpha) || hotnessAlpha < 0.0 || hotnessAlpha > 1.0) {
      throw new IllegalArgumentException("Hotness Alpha 必须在 0..1");
    }
    if (!Double.isFinite(halfLifeDays) || halfLifeDays <= 0.0) {
      throw new IllegalArgumentException("Half Life 必须大于零");
    }
    if (maxCandidates < topK || maxCandidates > 10000) {
      throw new IllegalArgumentException("Max Candidates 必须覆盖 Top K 且不超过 10000");
    }
    Objects.requireNonNull(requestedAt, "requestedAt");
  }
}
