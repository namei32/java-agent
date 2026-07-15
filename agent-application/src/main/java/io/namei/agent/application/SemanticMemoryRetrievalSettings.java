package io.namei.agent.application;

import java.util.Objects;

public record SemanticMemoryRetrievalSettings(
    String embeddingModel,
    int embeddingDimensions,
    int topK,
    double scoreThreshold,
    double hotnessAlpha,
    double halfLifeDays,
    int maxCandidates,
    int maxInjectedCharacters) {
  public SemanticMemoryRetrievalSettings {
    Objects.requireNonNull(embeddingModel, "embeddingModel");
    embeddingModel = embeddingModel.strip();
    if (embeddingModel.isBlank() || embeddingModel.length() > 256) {
      throw new IllegalArgumentException("Embedding Model 长度必须在 1..256");
    }
    if (embeddingDimensions < 1 || embeddingDimensions > 4096) {
      throw new IllegalArgumentException("Embedding 维度必须在 1..4096");
    }
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
    if (maxCandidates < topK || maxCandidates > 10_000) {
      throw new IllegalArgumentException("Max Candidates 必须覆盖 Top K 且不超过 10000");
    }
    if (maxInjectedCharacters < 1) {
      throw new IllegalArgumentException("Max Injected Characters 必须大于零");
    }
  }

  @Override
  public String toString() {
    return "SemanticMemoryRetrievalSettings[embeddingDimensions="
        + embeddingDimensions
        + ", topK="
        + topK
        + ", maxCandidates="
        + maxCandidates
        + ", maxInjectedCharacters="
        + maxInjectedCharacters
        + "]";
  }
}
