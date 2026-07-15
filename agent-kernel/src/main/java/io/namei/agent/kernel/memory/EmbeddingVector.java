package io.namei.agent.kernel.memory;

import java.util.Arrays;
import java.util.Objects;

public final class EmbeddingVector {
  private static final int MAX_DIMENSIONS = 4096;
  private final float[] values;

  public EmbeddingVector(float[] values) {
    Objects.requireNonNull(values, "values");
    if (values.length < 1 || values.length > MAX_DIMENSIONS) {
      throw new IllegalArgumentException("Embedding 维度必须在 1..4096");
    }
    this.values = values.clone();
    double normSquared = 0.0;
    for (float value : this.values) {
      if (!Float.isFinite(value)) {
        throw new IllegalArgumentException("Embedding 只能包含有限值");
      }
      normSquared += (double) value * value;
    }
    if (normSquared == 0.0) {
      throw new IllegalArgumentException("Embedding 范数必须大于零");
    }
  }

  public int dimensions() {
    return values.length;
  }

  public float[] values() {
    return values.clone();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || (other instanceof EmbeddingVector vector && Arrays.equals(values, vector.values));
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(values);
  }

  @Override
  public String toString() {
    return "EmbeddingVector[dimensions=" + dimensions() + "]";
  }
}
