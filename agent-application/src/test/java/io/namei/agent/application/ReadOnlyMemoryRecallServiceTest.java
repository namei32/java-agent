package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryCandidateLimitExceededException;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryStorePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("failure")
class ReadOnlyMemoryRecallServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
  private static final String BINDING = "a".repeat(64);

  @Test
  void filtersCurrentScopeAndTypeBeforeRankingAndTopK() {
    MemoryScope current = new MemoryScope(BINDING);
    var store =
        new RecordingStore(
            List.of(
                item("note-best", current, MemoryType.NOTE, vector(1.0, 0.0)),
                item("fact", current, MemoryType.FACT, vector(0.8, 0.6)),
                item(
                    "cross-scope",
                    new MemoryScope("b".repeat(64)),
                    MemoryType.FACT,
                    vector(1.0, 0.0))));
    var embedding = new RecordingEmbedding();

    List<String> ids =
        service(store, embedding).recall(BINDING, "cache", Optional.of(MemoryType.FACT), 1).stream()
            .map(hit -> hit.item().id())
            .toList();

    assertThat(ids).containsExactly("fact");
    assertThat(embedding.calls).isEqualTo(1);
    assertThat(store.request.scope()).isEqualTo(current);
    assertThat(store.request.topK()).isEqualTo(1);
  }

  @Test
  void enforcesCandidateLimitBeforeEmbedding() {
    var store =
        new RecordingStore(
            List.of(item("one", new MemoryScope(BINDING), MemoryType.NOTE, vector(1, 0))));
    store.candidateCount = 11;
    var embedding = new RecordingEmbedding();

    assertThatThrownBy(
            () -> service(store, embedding).recall(BINDING, "cache", Optional.empty(), 1))
        .isInstanceOf(MemoryCandidateLimitExceededException.class);
    assertThat(embedding.calls).isZero();
    assertThat(store.loads).isZero();
  }

  private static ReadOnlyMemoryRecallService service(
      MemoryStorePort store, EmbeddingPort embedding) {
    return new ReadOnlyMemoryRecallService(
        store,
        embedding,
        new SemanticMemoryRetrievalSettings("model", 2, 8, -1.0, 0.0, 14.0, 10, 6_000),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static MemoryItem item(
      String id, MemoryScope scope, MemoryType type, EmbeddingVector embedding) {
    return new MemoryItem(
        id,
        scope,
        type,
        "content-" + id,
        Integer.toHexString(id.hashCode()).repeat(16).substring(0, 64),
        embedding,
        "model",
        1,
        0,
        MemorySourceKind.EXPLICIT_API,
        null,
        1,
        NOW.minusSeconds(1),
        NOW);
  }

  private static EmbeddingVector vector(double first, double second) {
    return new EmbeddingVector(new float[] {(float) first, (float) second});
  }

  private static final class RecordingEmbedding implements EmbeddingPort {
    private int calls;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
      calls++;
      return new EmbeddingResult("model", 2, List.of(vector(1.0, 0.0)));
    }
  }

  private static final class RecordingStore implements MemoryStorePort {
    private final List<MemoryItem> items;
    private long candidateCount;
    private int loads;
    private MemorySearchRequest request;

    private RecordingStore(List<MemoryItem> items) {
      this.items = items;
      this.candidateCount = items.size();
    }

    @Override
    public long candidateCount(MemoryScope scope) {
      return candidateCount;
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
