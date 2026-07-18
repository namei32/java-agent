package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryCandidateLimitExceededException;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.port.MemoryStorePort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticMemorySearchTest {
  private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
  private static final MemoryScope SCOPE = new MemoryScope("a".repeat(64));
  private static final MemoryScope OTHER_SCOPE = new MemoryScope("b".repeat(64));
  private static final EmbeddingVector QUERY = vector(1.0, 0.0);

  @Test
  void matchesTheGoldenHotnessFormulaThresholdAndStableOrder() {
    var store =
        new RecordingStore(
            List.of(
                item("memory-a", SCOPE, vector(1.0, 0.0), "model", 1, 0, NOW),
                item(
                    "memory-b",
                    SCOPE,
                    vector(0.8, 0.6),
                    "model",
                    10,
                    10,
                    NOW.minusSeconds(14 * 86_400L)),
                item("memory-d", SCOPE, vector(0.6, 0.8), "model", 1, 0, NOW),
                item("memory-c", SCOPE, vector(0.6, 0.8), "model", 1, 0, NOW),
                item(
                    "memory-e",
                    SCOPE,
                    vector(0.44, Math.sqrt(1.0 - 0.44 * 0.44)),
                    "model",
                    100,
                    10,
                    NOW)));
    var search = new SemanticMemorySearch(store);

    List<MemorySearchHit> hits = search.search(request(4, 0.45, 0.2, 14.0, 100));

    assertThat(hits)
        .extracting(hit -> hit.item().id())
        .containsExactly("memory-a", "memory-b", "memory-c", "memory-d");
    assertThat(hits.get(0).semanticScore()).isCloseTo(1.0, offset(1.0e-12));
    assertThat(hits.get(0).hotnessScore()).isCloseTo(0.6666666666666666, offset(1.0e-12));
    assertThat(hits.get(0).finalScore()).isCloseTo(0.9333333333333333, offset(1.0e-12));
    assertThat(hits.get(1).semanticScore()).isCloseTo(0.8, offset(1.0e-7));
    assertThat(hits.get(1).hotnessScore()).isCloseTo(0.5774638145351502, offset(1.0e-12));
    assertThat(hits.get(1).finalScore()).isCloseTo(0.7554927629070302, offset(1.0e-7));
    assertThat(store.request).isEqualTo(request(4, 0.45, 0.2, 14.0, 100));
    assertThat(store.loads).isEqualTo(1);
    assertThatThrownBy(() -> hits.clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void filtersScopeModelAndDimensionsBeforeScoringAndAppliesTopK() {
    var matching = item("matching", SCOPE, vector(1.0, 0.0), "model", 1, 0, NOW);
    var second = item("second", SCOPE, vector(0.8, 0.6), "model", 1, 0, NOW);
    var store =
        new RecordingStore(
            List.of(
                item("wrong-scope", OTHER_SCOPE, vector(1.0, 0.0), "model", 1, 0, NOW),
                item("wrong-model", SCOPE, vector(1.0, 0.0), "other", 1, 0, NOW),
                item("wrong-dimensions", SCOPE, vector(1.0, 0.0, 0.0), "model", 1, 0, NOW),
                second,
                matching));

    List<MemorySearchHit> hits =
        new SemanticMemorySearch(store).search(request(1, -1.0, 0.0, 14.0, 100));

    assertThat(hits).singleElement().extracting(hit -> hit.item().id()).isEqualTo("matching");
  }

  @Test
  void appliesTheCallerTypeFilterBeforeRankingAndTopK() {
    var note = item("note", SCOPE, vector(1.0, 0.0), "model", 1, 0, NOW);
    var fact = item("fact", SCOPE, vector(0.8, 0.6), "model", 1, 0, NOW);
    fact =
        new MemoryItem(
            fact.id(),
            fact.scope(),
            MemoryType.FACT,
            fact.content(),
            fact.contentHash(),
            fact.embedding(),
            fact.embeddingModel(),
            fact.reinforcement(),
            fact.emotionalWeight(),
            fact.sourceKind(),
            fact.happenedAt(),
            fact.revision(),
            fact.createdAt(),
            fact.updatedAt());

    List<MemorySearchHit> hits =
        new SemanticMemorySearch(new RecordingStore(List.of(note, fact)))
            .search(request(1, -1.0, 0.0, 14.0, 100), item -> item.type() == MemoryType.FACT);

    assertThat(hits).singleElement().extracting(hit -> hit.item().id()).isEqualTo("fact");
  }

  @Test
  void clampsFutureAgeToZeroAndUsesEmotionalWeightToExtendHalfLife() {
    var future = item("future", SCOPE, vector(1.0, 0.0), "model", 1, 0, NOW.plusSeconds(3600));
    var emotional =
        item("emotional", SCOPE, vector(1.0, 0.0), "model", 1, 10, NOW.minusSeconds(14 * 86_400L));
    var ordinary =
        item("ordinary", SCOPE, vector(1.0, 0.0), "model", 1, 0, NOW.minusSeconds(14 * 86_400L));

    List<MemorySearchHit> hits =
        new SemanticMemorySearch(new RecordingStore(List.of(ordinary, emotional, future)))
            .search(request(3, 0.45, 1.0, 14.0, 100));

    assertThat(hits)
        .extracting(hit -> hit.item().id())
        .containsExactly("future", "emotional", "ordinary");
    assertThat(hits.getFirst().hotnessScore()).isCloseTo(0.6666666666666666, offset(1.0e-12));
    assertThat(hits.get(1).hotnessScore()).isGreaterThan(hits.get(2).hotnessScore());
  }

  @Test
  void rejectsAnOverLimitCandidateResultInsteadOfSilentlyTruncating() {
    var store =
        new RecordingStore(
            List.of(
                item("one", SCOPE, QUERY, "model", 1, 0, NOW),
                item("two", SCOPE, QUERY, "model", 1, 0, NOW),
                item("three", SCOPE, QUERY, "model", 1, 0, NOW)));

    assertThatThrownBy(() -> new SemanticMemorySearch(store).search(request(1, 0.45, 0.2, 14.0, 2)))
        .isInstanceOf(MemoryCandidateLimitExceededException.class)
        .hasMessage("Memory 候选数量超过上限");
  }

  private static MemorySearchRequest request(
      int topK, double threshold, double alpha, double halfLifeDays, int maxCandidates) {
    return new MemorySearchRequest(
        SCOPE, QUERY, "model", topK, threshold, alpha, halfLifeDays, maxCandidates, NOW);
  }

  private static MemoryItem item(
      String id,
      MemoryScope scope,
      EmbeddingVector embedding,
      String model,
      int reinforcement,
      int emotionalWeight,
      Instant updatedAt) {
    return new MemoryItem(
        id,
        scope,
        MemoryType.NOTE,
        "content-" + id,
        Integer.toHexString(id.hashCode()).repeat(16).substring(0, 64),
        embedding,
        model,
        reinforcement,
        emotionalWeight,
        MemorySourceKind.EXPLICIT_API,
        null,
        1,
        updatedAt.minusSeconds(1),
        updatedAt);
  }

  private static EmbeddingVector vector(double... values) {
    var result = new float[values.length];
    for (int index = 0; index < values.length; index++) {
      result[index] = (float) values[index];
    }
    return new EmbeddingVector(result);
  }

  private static final class RecordingStore implements MemoryStorePort {
    private final List<MemoryItem> items;
    private int loads;
    private MemorySearchRequest request;

    private RecordingStore(List<MemoryItem> items) {
      this.items = items;
    }

    @Override
    public long candidateCount(MemoryScope scope) {
      return items.size();
    }

    @Override
    public List<MemoryItem> loadCandidates(MemorySearchRequest request) {
      loads++;
      this.request = request;
      return items;
    }

    @Override
    public List<MemoryItem> list(MemoryScope scope, int limit) {
      return items;
    }
  }
}
