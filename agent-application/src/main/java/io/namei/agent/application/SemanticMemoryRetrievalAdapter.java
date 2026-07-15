package io.namei.agent.application;

import io.namei.agent.kernel.error.EmbeddingInvocationException;
import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryCandidateLimitExceededException;
import io.namei.agent.kernel.memory.MemoryRetrievalRequest;
import io.namei.agent.kernel.memory.MemoryRetrievalResult;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.port.MemoryStorePort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SemanticMemoryRetrievalAdapter implements MemoryRetrievalPort {
  private static final int MAX_RULES = 4;
  private static final int MAX_RELATED = 4;

  private final MemoryStorePort store;
  private final EmbeddingPort embeddings;
  private final SemanticMemoryRetrievalSettings settings;
  private final SemanticMemorySearch search;
  private final MemoryInjectionFormatter formatter;

  public SemanticMemoryRetrievalAdapter(
      MemoryStorePort store, EmbeddingPort embeddings, SemanticMemoryRetrievalSettings settings) {
    this.store = Objects.requireNonNull(store, "store");
    this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.search = new SemanticMemorySearch(store);
    this.formatter = new MemoryInjectionFormatter();
  }

  @Override
  public MemoryRetrievalResult retrieve(MemoryRetrievalRequest request) {
    Objects.requireNonNull(request, "request");
    var scope = new MemoryScope(request.sessionBinding());
    long candidateCount = store.candidateCount(scope);
    if (candidateCount < 0) {
      throw new IllegalStateException("Memory candidate count 无效");
    }
    if (candidateCount == 0) {
      return MemoryRetrievalResult.empty();
    }
    if (candidateCount > settings.maxCandidates()) {
      throw new MemoryCandidateLimitExceededException();
    }

    Optional<EmbeddingVector> queryEmbedding = queryEmbedding(request.currentMessage());
    if (queryEmbedding.isEmpty()) {
      return MemoryRetrievalResult.degraded();
    }

    var searchRequest =
        new MemorySearchRequest(
            scope,
            queryEmbedding.orElseThrow(),
            settings.embeddingModel(),
            settings.topK(),
            settings.scoreThreshold(),
            settings.hotnessAlpha(),
            settings.halfLifeDays(),
            settings.maxCandidates(),
            request.requestedAt());
    var hits = search.search(searchRequest);
    if (hits.isEmpty()) {
      return MemoryRetrievalResult.empty();
    }

    MemoryInjection injection =
        formatter.format(hits, MAX_RULES, MAX_RELATED, settings.maxInjectedCharacters());
    if (injection.injectedCount() == 0) {
      return MemoryRetrievalResult.empty();
    }
    return MemoryRetrievalResult.retrieved(injection.block(), injection.injectedCount());
  }

  private Optional<EmbeddingVector> queryEmbedding(String currentMessage) {
    try {
      EmbeddingResult result = embeddings.embed(new EmbeddingRequest(List.of(currentMessage)));
      if (result == null) {
        throw new InvalidEmbeddingResponseException();
      }
      if (!settings.embeddingModel().equals(result.model())
          || result.dimensions() != settings.embeddingDimensions()
          || result.vectors().size() != 1) {
        throw new InvalidEmbeddingResponseException();
      }
      EmbeddingVector vector = result.vectors().getFirst();
      if (vector.dimensions() != settings.embeddingDimensions()) {
        throw new InvalidEmbeddingResponseException();
      }
      return Optional.of(vector);
    } catch (EmbeddingInvocationException | InvalidEmbeddingResponseException exception) {
      return Optional.empty();
    }
  }
}
