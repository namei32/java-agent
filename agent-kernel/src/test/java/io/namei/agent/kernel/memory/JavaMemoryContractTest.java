package io.namei.agent.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.port.MemoryStorePort;
import io.namei.agent.kernel.port.MemoryWritePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JavaMemoryContractTest {
  private static final Instant HAPPENED_AT = Instant.parse("2026-07-15T04:00:00Z");
  private static final Instant NOW = Instant.parse("2026-07-15T05:00:00Z");
  private static final MemoryScope SCOPE = new MemoryScope("a".repeat(64));
  private static final EmbeddingVector VECTOR = new EmbeddingVector(new float[] {1.0f, 0.0f});

  @Test
  void fixesModesStatusesAndMemoryKinds() {
    assertThat(MemoryRuntimeMode.values())
        .containsExactly(
            MemoryRuntimeMode.DISABLED, MemoryRuntimeMode.READ_ONLY, MemoryRuntimeMode.JAVA_NATIVE);
    assertThat(MemoryRetrievalStatus.values())
        .containsExactly(
            MemoryRetrievalStatus.DISABLED,
            MemoryRetrievalStatus.EMPTY,
            MemoryRetrievalStatus.RETRIEVED,
            MemoryRetrievalStatus.DEGRADED);
    assertThat(MemoryType.values())
        .containsExactly(
            MemoryType.NOTE,
            MemoryType.FACT,
            MemoryType.PREFERENCE,
            MemoryType.PROCEDURE,
            MemoryType.EVENT);
    assertThat(MemorySourceKind.values())
        .containsExactly(MemorySourceKind.EXPLICIT_API, MemorySourceKind.PROACTIVE_APPROVED);
    assertThat(MemoryWriteStatus.values())
        .containsExactly(MemoryWriteStatus.CREATED, MemoryWriteStatus.REINFORCED);
    assertThat(MemoryDeleteStatus.values())
        .containsExactly(MemoryDeleteStatus.DELETED, MemoryDeleteStatus.NOT_FOUND);
    assertThat(MemoryRetrievalResult.degraded().trace().status())
        .isEqualTo(MemoryRetrievalStatus.DEGRADED);
  }

  @Test
  void validatesAndRedactsScopeAndMemoryItems() {
    var item = item();

    assertThat(SCOPE.binding()).isEqualTo("a".repeat(64));
    assertThat(SCOPE.toString()).isEqualTo("MemoryScope[redacted]");
    assertThat(item.embedding()).isEqualTo(VECTOR);
    assertThat(item.embeddingDimensions()).isEqualTo(2);
    assertThat(item.toString())
        .contains("PREFERENCE", "reinforcement=1", "revision=1")
        .doesNotContain(
            item.id(), item.content(), item.contentHash(), SCOPE.binding(), "embedding-model");

    assertThatThrownBy(() -> new MemoryScope("A".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryScope("a".repeat(63)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemoryItem(
                    "memory-0001",
                    SCOPE,
                    MemoryType.NOTE,
                    " ",
                    "b".repeat(64),
                    VECTOR,
                    "model",
                    1,
                    0,
                    MemorySourceKind.EXPLICIT_API,
                    null,
                    1,
                    NOW,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fixesWriteDeleteAndMutationIdempotencyShapes() {
    var write = writeCommand();
    var created = new MemoryWriteResult(MemoryWriteStatus.CREATED, item());
    var delete =
        new MemoryDeleteCommand(SCOPE, "req-delete-001", "memory-0001", "d".repeat(64), NOW);
    var deleted = new MemoryDeleteResult(MemoryDeleteStatus.DELETED, "memory-0001");
    var mutation =
        new MemoryMutation(
            MemoryMutationOperation.UPSERT,
            "c".repeat(64),
            "memory-0001",
            MemoryMutationStatus.CREATED,
            NOW);

    assertThat(write.content()).isEqualTo("回答时 先给结论");
    assertThat(created.item()).isEqualTo(item());
    assertThat(delete.requestId()).isEqualTo("req-delete-001");
    assertThat(deleted.status()).isEqualTo(MemoryDeleteStatus.DELETED);
    assertThat(mutation.toString()).doesNotContain(mutation.argumentHash(), mutation.itemId());

    assertThatThrownBy(
            () -> new MemoryDeleteCommand(SCOPE, "bad request", "memory-0001", "d".repeat(64), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MemoryMutation(
                    MemoryMutationOperation.DELETE,
                    "d".repeat(64),
                    "memory-0001",
                    MemoryMutationStatus.CREATED,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesSearchInputsAndStableHitScores() {
    var request =
        new MemorySearchRequest(SCOPE, VECTOR, "embedding-model", 4, 0.45, 0.2, 14.0, 100, NOW);
    var hit = new MemorySearchHit(item(), 0.8, 0.6, 0.76);

    assertThat(request.queryEmbedding()).isEqualTo(VECTOR);
    assertThat(hit.finalScore()).isEqualTo(0.76);
    assertThat(hit.toString()).doesNotContain(item().content(), item().id(), SCOPE.binding());

    assertThatThrownBy(
            () -> new MemorySearchRequest(SCOPE, VECTOR, "model", 0, 0.45, 0.2, 14.0, 100, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new MemorySearchRequest(SCOPE, VECTOR, "model", 8, 0.45, 1.1, 14.0, 100, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemorySearchHit(item(), Double.NaN, 0.6, 0.76))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exposesNarrowReadAndWritePortsWithoutFrameworkTypes() {
    var item = item();
    MemoryStorePort store =
        new MemoryStorePort() {
          @Override
          public long candidateCount(MemoryScope scope) {
            return 1;
          }

          @Override
          public List<MemoryItem> loadCandidates(MemorySearchRequest request) {
            return List.of(item);
          }

          @Override
          public List<MemoryItem> list(MemoryScope scope, int limit) {
            return List.of(item);
          }
        };
    MemoryWritePort writer =
        new MemoryWritePort() {
          @Override
          public Optional<MemoryMutation> findMutation(MemoryMutationKey key) {
            return Optional.empty();
          }

          @Override
          public Optional<MemoryWriteResult> replayUpsert(MemoryWriteReplayQuery query) {
            return Optional.empty();
          }

          @Override
          public MemoryWriteResult upsert(MemoryWriteCommand command) {
            return new MemoryWriteResult(MemoryWriteStatus.CREATED, item);
          }

          @Override
          public MemoryDeleteResult delete(MemoryDeleteCommand command) {
            return new MemoryDeleteResult(MemoryDeleteStatus.DELETED, command.itemId());
          }
        };

    assertThat(store.candidateCount(SCOPE)).isEqualTo(1);
    assertThat(store.loadCandidates(searchRequest())).containsExactly(item);
    assertThat(store.list(SCOPE, 100)).containsExactly(item);
    assertThat(writer.findMutation(new MemoryMutationKey(SCOPE, "req-write-001"))).isEmpty();
    assertThat(
            writer.replayUpsert(
                new MemoryWriteReplayQuery(
                    new MemoryMutationKey(SCOPE, "req-write-001"), "c".repeat(64))))
        .isEmpty();
    assertThat(writer.upsert(writeCommand()).status()).isEqualTo(MemoryWriteStatus.CREATED);
  }

  private static MemorySearchRequest searchRequest() {
    return new MemorySearchRequest(SCOPE, VECTOR, "embedding-model", 4, 0.45, 0.2, 14.0, 100, NOW);
  }

  private static MemoryWriteCommand writeCommand() {
    return new MemoryWriteCommand(
        SCOPE,
        "req-write-001",
        "memory-0001",
        MemoryType.PREFERENCE,
        "回答时 先给结论",
        "b".repeat(64),
        VECTOR,
        "embedding-model",
        2,
        MemorySourceKind.EXPLICIT_API,
        HAPPENED_AT,
        "c".repeat(64),
        NOW);
  }

  private static MemoryItem item() {
    return new MemoryItem(
        "memory-0001",
        SCOPE,
        MemoryType.PREFERENCE,
        "回答时 先给结论",
        "b".repeat(64),
        VECTOR,
        "embedding-model",
        1,
        2,
        MemorySourceKind.EXPLICIT_API,
        HAPPENED_AT,
        1,
        NOW,
        NOW);
  }
}
