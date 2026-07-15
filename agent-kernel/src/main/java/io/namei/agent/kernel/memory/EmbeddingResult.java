package io.namei.agent.kernel.memory;

import java.util.List;
import java.util.Objects;

public record EmbeddingResult(String model, int dimensions, List<EmbeddingVector> vectors) {
  public EmbeddingResult {
    model = MemoryValueRules.required(model, "Embedding Model", 256);
    if (dimensions < 1 || dimensions > 4096) {
      throw new IllegalArgumentException("Embedding 维度必须在 1..4096");
    }
    vectors = List.copyOf(Objects.requireNonNull(vectors, "vectors"));
    for (EmbeddingVector vector : vectors) {
      Objects.requireNonNull(vector, "embedding vector");
      if (vector.dimensions() != dimensions) {
        throw new IllegalArgumentException("Embedding 结果维度不一致");
      }
    }
  }
}
