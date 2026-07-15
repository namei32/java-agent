package io.namei.agent.application;

import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryCandidateLimitExceededException;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.port.MemoryStorePort;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class SemanticMemorySearch {
  private static final double SECONDS_PER_DAY = 86_400.0;
  private static final double MINIMUM_HALF_LIFE_DAYS = 0.1;
  private static final Comparator<MemorySearchHit> STABLE_ORDER =
      Comparator.comparingDouble(MemorySearchHit::finalScore)
          .reversed()
          .thenComparing(Comparator.comparingDouble(MemorySearchHit::semanticScore).reversed())
          .thenComparing(hit -> hit.item().updatedAt(), Comparator.reverseOrder())
          .thenComparing(hit -> hit.item().id());

  private final MemoryStorePort store;

  public SemanticMemorySearch(MemoryStorePort store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public List<MemorySearchHit> search(MemorySearchRequest request) {
    Objects.requireNonNull(request, "request");
    List<MemoryItem> candidates =
        List.copyOf(Objects.requireNonNull(store.loadCandidates(request), "candidates"));
    if (candidates.size() > request.maxCandidates()) {
      throw new MemoryCandidateLimitExceededException();
    }

    return candidates.stream()
        .filter(item -> item.scope().equals(request.scope()))
        .filter(item -> item.embeddingModel().equals(request.embeddingModel()))
        .filter(item -> item.embeddingDimensions() == request.queryEmbedding().dimensions())
        .map(
            item -> new SemanticCandidate(item, cosine(request.queryEmbedding(), item.embedding())))
        .filter(candidate -> candidate.semanticScore() >= request.scoreThreshold())
        .map(candidate -> score(request, candidate))
        .sorted(STABLE_ORDER)
        .limit(request.topK())
        .toList();
  }

  private static MemorySearchHit score(MemorySearchRequest request, SemanticCandidate candidate) {
    double semantic = candidate.semanticScore();
    double hotness = hotness(request, candidate.item());
    double combined = (1.0 - request.hotnessAlpha()) * semantic + request.hotnessAlpha() * hotness;
    return new MemorySearchHit(candidate.item(), semantic, hotness, clamp(combined, -1.0, 1.0));
  }

  private static double cosine(EmbeddingVector left, EmbeddingVector right) {
    float[] leftValues = left.values();
    float[] rightValues = right.values();
    double dot = 0.0;
    double leftNormSquared = 0.0;
    double rightNormSquared = 0.0;
    for (int index = 0; index < leftValues.length; index++) {
      double leftValue = leftValues[index];
      double rightValue = rightValues[index];
      dot += leftValue * rightValue;
      leftNormSquared += leftValue * leftValue;
      rightNormSquared += rightValue * rightValue;
    }
    double cosine = dot / Math.sqrt(leftNormSquared * rightNormSquared);
    return clamp(cosine, -1.0, 1.0);
  }

  private static double hotness(MemorySearchRequest request, MemoryItem item) {
    double emotionalExtension = 1.0 + 0.5 * item.emotionalWeight() / 10.0;
    double effectiveHalfLife =
        Math.max(request.halfLifeDays() * emotionalExtension, MINIMUM_HALF_LIFE_DAYS);
    double frequency = 1.0 / (1.0 + Math.exp(-Math.log1p(item.reinforcement())));
    double ageDays = ageDays(item, request);
    double recency = Math.exp(-Math.log(2.0) / effectiveHalfLife * ageDays);
    return clamp(frequency * recency, 0.0, 1.0);
  }

  private static double ageDays(MemoryItem item, MemorySearchRequest request) {
    Duration age = Duration.between(item.updatedAt(), request.requestedAt());
    if (age.isNegative()) {
      return 0.0;
    }
    return age.getSeconds() / SECONDS_PER_DAY + age.getNano() / (SECONDS_PER_DAY * 1_000_000_000.0);
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private record SemanticCandidate(MemoryItem item, double semanticScore) {}
}
