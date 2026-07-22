package io.namei.agent.application;

import io.namei.agent.kernel.error.EmbeddingInvocationException;
import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryCandidateLimitExceededException;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryStorePort;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 仅供当前 Scope 只读 Tool 使用的有界 Java Native 检索。 */
public final class ReadOnlyMemoryRecallService {
  private final MemoryStorePort store;
  private final EmbeddingPort embeddings;
  private final SemanticMemoryRetrievalSettings settings;
  private final SemanticMemorySearch search;
  private final Clock clock;

  public ReadOnlyMemoryRecallService(
      MemoryStorePort store,
      EmbeddingPort embeddings,
      SemanticMemoryRetrievalSettings settings,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.search = new SemanticMemorySearch(store);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public List<MemorySearchHit> recall(
      String sessionBinding, String query, Optional<MemoryType> memoryType, int limit) {
    Objects.requireNonNull(sessionBinding, "sessionBinding");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(memoryType, "memoryType");
    MemoryScope scope = new MemoryScope(sessionBinding);
    long candidateCount = store.candidateCount(scope);
    if (candidateCount < 0) {
      throw new IllegalStateException("Memory candidate count 无效");
    }
    if (candidateCount == 0) {
      return List.of();
    }
    if (candidateCount > settings.maxCandidates()) {
      throw new MemoryCandidateLimitExceededException();
    }
    EmbeddingVector queryEmbedding = queryEmbedding(query);
    var request =
        new MemorySearchRequest(
            scope,
            queryEmbedding,
            settings.embeddingModel(),
            limit,
            settings.scoreThreshold(),
            settings.hotnessAlpha(),
            settings.halfLifeDays(),
            settings.maxCandidates(),
            clock.instant());
    return search.search(request, item -> memoryType.map(type -> type == item.type()).orElse(true));
  }

  private EmbeddingVector queryEmbedding(String query) {
    try {
      EmbeddingResult result = embeddings.embed(new EmbeddingRequest(List.of(query)));
      if (result == null
          || !settings.embeddingModel().equals(result.model())
          || result.dimensions() != settings.embeddingDimensions()
          || result.vectors().size() != 1) {
        throw new InvalidEmbeddingResponseException();
      }
      EmbeddingVector vector = result.vectors().getFirst();
      if (vector.dimensions() != settings.embeddingDimensions()) {
        throw new InvalidEmbeddingResponseException();
      }
      return vector;
    } catch (EmbeddingInvocationException | InvalidEmbeddingResponseException exception) {
      throw new IllegalStateException("Memory recall embedding 不可用", exception);
    }
  }
}
