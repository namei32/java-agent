package io.namei.agent.kernel.memory;

import java.util.Objects;

public record MemorySearchHit(
    MemoryItem item, double semanticScore, double hotnessScore, double finalScore) {
  public MemorySearchHit {
    Objects.requireNonNull(item, "item");
    bounded(semanticScore, -1.0, 1.0, "Semantic Score");
    bounded(hotnessScore, 0.0, 1.0, "Hotness Score");
    bounded(finalScore, -1.0, 1.0, "Final Score");
  }

  private static void bounded(double value, double minimum, double maximum, String field) {
    if (!Double.isFinite(value) || value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " 超出允许范围");
    }
  }

  @Override
  public String toString() {
    return "MemorySearchHit[semanticScore="
        + semanticScore
        + ", hotnessScore="
        + hotnessScore
        + ", finalScore="
        + finalScore
        + "]";
  }
}
