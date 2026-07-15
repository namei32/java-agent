package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryCandidateLimitExceededException;
import io.namei.agent.kernel.memory.MemoryDeleteCommand;
import io.namei.agent.kernel.memory.MemoryDeleteStatus;
import io.namei.agent.kernel.memory.MemoryIdempotencyConflictException;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.memory.MemoryMutationOperation;
import io.namei.agent.kernel.memory.MemoryMutationStatus;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.memory.MemoryWriteReplayQuery;
import io.namei.agent.kernel.memory.MemoryWriteResult;
import io.namei.agent.kernel.memory.MemoryWriteStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcJavaMemoryStoreTest {
  private static final Instant HAPPENED_AT = Instant.parse("2026-07-15T04:00:00Z");
  private static final Instant NOW = Instant.parse("2026-07-15T05:00:00Z");
  private static final MemoryScope SCOPE_A = scope('a');
  private static final MemoryScope SCOPE_B = scope('b');
  private static final EmbeddingVector VECTOR_A = new EmbeddingVector(new float[] {1.0f, 0.0f});
  private static final EmbeddingVector VECTOR_B = new EmbeddingVector(new float[] {0.0f, 1.0f});

  @TempDir Path tempDir;
  private JavaMemorySchemaInitializer schema;
  private JdbcJavaMemoryStore store;

  @BeforeEach
  void setUp() {
    schema =
        new JavaMemorySchemaInitializer(tempDir.resolve("workspace/memory/agent-memory.db"), 5_000);
    schema.initialize();
    store = new JdbcJavaMemoryStore(schema, new Float32VectorCodec());
  }

  @Test
  void insertsListsAndReadsParameterizedContentWithinOneScope() throws Exception {
    String content = "'); DROP TABLE memory_items; --";
    var result = store.upsert(command(SCOPE_A, "req-1", "memory-1", MemoryType.NOTE, content));

    assertThat(result.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(result.item().content()).isEqualTo(content);
    assertThat(result.item().embedding()).isEqualTo(VECTOR_A);
    assertThat(store.candidateCount(SCOPE_A)).isEqualTo(1);
    assertThat(store.list(SCOPE_A, 100)).containsExactly(result.item());
    assertThat(store.list(SCOPE_B, 100)).isEmpty();
    assertThat(rawString("SELECT hex(embedding) FROM memory_items")).isEqualTo("0000803F00000000");
    assertThat(rawLong("SELECT COUNT(*) FROM memory_items")).isEqualTo(1);

    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute("BEGIN EXCLUSIVE");
      statement.execute("ROLLBACK");
    }
  }

  @Test
  void reinforcesExactContentButPreservesTheOriginalPayloadAndEmbedding() {
    var first =
        store.upsert(
            command(
                SCOPE_A,
                "req-1",
                "memory-1",
                MemoryType.PREFERENCE,
                "回答时先给结论",
                'c',
                'd',
                VECTOR_A,
                "model-a",
                2,
                HAPPENED_AT,
                NOW));
    var secondAt = NOW.plusSeconds(60);
    var second =
        store.upsert(
            command(
                SCOPE_A,
                "req-2",
                "memory-2",
                MemoryType.PREFERENCE,
                "回答时先给结论",
                'c',
                'e',
                VECTOR_B,
                "model-b",
                7,
                HAPPENED_AT.plusSeconds(30),
                secondAt));

    assertThat(first.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(second.status()).isEqualTo(MemoryWriteStatus.REINFORCED);
    assertThat(second.item().id()).isEqualTo("memory-1");
    assertThat(second.item().reinforcement()).isEqualTo(2);
    assertThat(second.item().revision()).isEqualTo(2);
    assertThat(second.item().emotionalWeight()).isEqualTo(7);
    assertThat(second.item().embedding()).isEqualTo(VECTOR_A);
    assertThat(second.item().embeddingModel()).isEqualTo("model-a");
    assertThat(second.item().happenedAt()).isEqualTo(HAPPENED_AT);
    assertThat(second.item().updatedAt()).isEqualTo(secondAt);
    assertThat(store.candidateCount(SCOPE_A)).isEqualTo(1);
  }

  @Test
  void keepsDifferentScopesAndTypesIndependent() {
    var scopeA =
        store.upsert(command(SCOPE_A, "req-a-note", "memory-a-note", MemoryType.NOTE, "same"));
    var scopeB =
        store.upsert(command(SCOPE_B, "req-b-note", "memory-b-note", MemoryType.NOTE, "same"));
    var differentType =
        store.upsert(command(SCOPE_A, "req-a-fact", "memory-a-fact", MemoryType.FACT, "same"));

    assertThat(scopeA.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(scopeB.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(differentType.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(store.list(SCOPE_A, 100)).hasSize(2);
    assertThat(store.list(SCOPE_B, 100)).hasSize(1);
  }

  @Test
  void returnsTheRecordedMutationOnRetryAndRejectsChangedArguments() {
    var command = command(SCOPE_A, "req-1", "memory-1", MemoryType.NOTE, "idempotent");

    var first = store.upsert(command);
    var retried = store.upsert(command);

    assertThat(first.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(retried.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(retried.item().reinforcement()).isEqualTo(1);
    assertThat(store.candidateCount(SCOPE_A)).isEqualTo(1);
    assertThat(store.findMutation(new MemoryMutationKey(SCOPE_A, "req-1")))
        .hasValueSatisfying(
            mutation -> {
              assertThat(mutation.operation()).isEqualTo(MemoryMutationOperation.UPSERT);
              assertThat(mutation.status()).isEqualTo(MemoryMutationStatus.CREATED);
              assertThat(mutation.argumentHash()).isEqualTo(command.argumentHash());
            });
    assertThat(rawLong("SELECT COUNT(*) FROM memory_mutations")).isEqualTo(1);
    assertThat(
            store.replayUpsert(
                new MemoryWriteReplayQuery(
                    new MemoryMutationKey(SCOPE_A, "req-1"), command.argumentHash())))
        .contains(first);
    assertThat(
            store.replayUpsert(
                new MemoryWriteReplayQuery(
                    new MemoryMutationKey(SCOPE_A, "missing"), command.argumentHash())))
        .isEmpty();

    var changed =
        command(
            SCOPE_A,
            "req-1",
            "memory-1",
            MemoryType.NOTE,
            "idempotent",
            'b',
            'f',
            VECTOR_A,
            "model-a",
            0,
            HAPPENED_AT,
            NOW);
    assertThatThrownBy(() -> store.upsert(changed))
        .isInstanceOf(MemoryIdempotencyConflictException.class)
        .hasMessage("Memory Request ID 已绑定其他参数");
    assertThatThrownBy(
            () ->
                store.replayUpsert(
                    new MemoryWriteReplayQuery(
                        new MemoryMutationKey(SCOPE_A, "req-1"), changed.argumentHash())))
        .isInstanceOf(MemoryIdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                store.delete(
                    new MemoryDeleteCommand(
                        SCOPE_A, "req-1", "memory-1", command.argumentHash(), NOW)))
        .isInstanceOf(MemoryIdempotencyConflictException.class);
    assertThat(store.list(SCOPE_A, 100).getFirst().reinforcement()).isEqualTo(1);
  }

  @Test
  void serializesConcurrentRetriesWithoutDuplicatingTheWrite() throws Exception {
    var command = command(SCOPE_A, "req-1", "memory-1", MemoryType.NOTE, "concurrent");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> upsertAfter(start, command));
      var second = executor.submit(() -> upsertAfter(start, command));
      start.countDown();

      assertThat(List.of(first.get().status(), second.get().status()))
          .containsExactly(MemoryWriteStatus.CREATED, MemoryWriteStatus.CREATED);
    }
    assertThat(store.list(SCOPE_A, 100))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.reinforcement()).isEqualTo(1);
              assertThat(item.revision()).isEqualTo(1);
            });
    assertThat(rawLong("SELECT COUNT(*) FROM memory_mutations")).isEqualTo(1);
  }

  @Test
  void serializesConcurrentExactContentIntoOneReinforcedItem() throws Exception {
    var firstCommand = command(SCOPE_A, "req-1", "memory-1", MemoryType.NOTE, "same-content");
    var secondCommand =
        command(
            SCOPE_A,
            "req-2",
            "memory-2",
            MemoryType.NOTE,
            "same-content",
            'b',
            'd',
            VECTOR_B,
            "model-b",
            4,
            HAPPENED_AT.plusSeconds(1),
            NOW.plusSeconds(1));
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> upsertAfter(start, firstCommand));
      var second = executor.submit(() -> upsertAfter(start, secondCommand));
      start.countDown();

      assertThat(List.of(first.get().status(), second.get().status()))
          .containsExactlyInAnyOrder(MemoryWriteStatus.CREATED, MemoryWriteStatus.REINFORCED);
    }
    assertThat(store.list(SCOPE_A, 100))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.reinforcement()).isEqualTo(2);
              assertThat(item.revision()).isEqualTo(2);
              assertThat(item.emotionalWeight()).isEqualTo(4);
              assertThat(item.updatedAt()).isEqualTo(NOW.plusSeconds(1));
            });
    assertThat(rawLong("SELECT COUNT(*) FROM memory_mutations")).isEqualTo(2);
  }

  @Test
  void rollsBackItemAndLedgerWhenWritingTheMutationFails() throws Exception {
    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TRIGGER fail_upsert_ledger BEFORE INSERT ON memory_mutations
          WHEN NEW.operation = 'UPSERT'
          BEGIN SELECT RAISE(ABORT, 'sensitive-trigger-message'); END
          """);
    }

    assertThatThrownBy(
            () -> store.upsert(command(SCOPE_A, "req-1", "memory-1", MemoryType.NOTE, "rollback")))
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .hasMessage("Java Memory 操作失败")
        .hasMessageNotContaining("sensitive-trigger-message")
        .extracting(exception -> ((JavaMemoryRepositoryException) exception).failure())
        .isEqualTo(JavaMemoryRepositoryFailure.OPERATION_FAILED);
    assertThat(rawLong("SELECT COUNT(*) FROM memory_items")).isZero();
    assertThat(rawLong("SELECT COUNT(*) FROM memory_mutations")).isZero();
  }

  @Test
  void physicallyDeletesWithinScopeAndKeepsDeleteRetriesIdempotent() {
    store.upsert(command(SCOPE_A, "req-write", "memory-1", MemoryType.NOTE, "delete-me"));

    var crossScope =
        store.delete(
            new MemoryDeleteCommand(SCOPE_B, "req-cross", "memory-1", "e".repeat(64), NOW));
    assertThat(crossScope.status()).isEqualTo(MemoryDeleteStatus.NOT_FOUND);
    assertThat(store.candidateCount(SCOPE_A)).isEqualTo(1);

    var delete = new MemoryDeleteCommand(SCOPE_A, "req-delete", "memory-1", "f".repeat(64), NOW);
    var deleted = store.delete(delete);
    var retried = store.delete(delete);

    assertThat(deleted.status()).isEqualTo(MemoryDeleteStatus.DELETED);
    assertThat(retried.status()).isEqualTo(MemoryDeleteStatus.DELETED);
    assertThat(store.list(SCOPE_A, 100)).isEmpty();
    assertThat(rawLong("SELECT COUNT(*) FROM memory_items WHERE content = 'delete-me'")).isZero();
    assertThat(rawLong("SELECT COUNT(*) FROM memory_items WHERE embedding IS NOT NULL")).isZero();
    assertThat(
            store.delete(
                new MemoryDeleteCommand(
                    SCOPE_A, "req-delete-again", "memory-1", "1".repeat(64), NOW)))
        .extracting(result -> result.status())
        .isEqualTo(MemoryDeleteStatus.NOT_FOUND);
  }

  @Test
  void failsClosedInsteadOfResurrectingAWriteThatWasPhysicallyDeleted() {
    var write = command(SCOPE_A, "req-write", "memory-1", MemoryType.NOTE, "forget-me");
    store.upsert(write);
    store.delete(
        new MemoryDeleteCommand(
            SCOPE_A, "req-delete", "memory-1", "f".repeat(64), NOW.plusSeconds(1)));

    assertThatThrownBy(
            () ->
                store.replayUpsert(
                    new MemoryWriteReplayQuery(
                        new MemoryMutationKey(SCOPE_A, "req-write"), write.argumentHash())))
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .extracting(exception -> ((JavaMemoryRepositoryException) exception).failure())
        .isEqualTo(JavaMemoryRepositoryFailure.OPERATION_FAILED);
    assertThatThrownBy(() -> store.upsert(write))
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .extracting(exception -> ((JavaMemoryRepositoryException) exception).failure())
        .isEqualTo(JavaMemoryRepositoryFailure.OPERATION_FAILED);
    assertThat(store.candidateCount(SCOPE_A)).isZero();
    assertThat(rawLong("SELECT COUNT(*) FROM memory_mutations")).isEqualTo(2);
  }

  @Test
  void rollsBackPhysicalDeleteWhenItsLedgerWriteFails() throws Exception {
    store.upsert(command(SCOPE_A, "req-write", "memory-1", MemoryType.NOTE, "keep-on-failure"));
    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TRIGGER fail_delete_ledger BEFORE INSERT ON memory_mutations
          WHEN NEW.operation = 'DELETE'
          BEGIN SELECT RAISE(ABORT, 'delete-ledger-failed'); END
          """);
    }

    assertThatThrownBy(
            () ->
                store.delete(
                    new MemoryDeleteCommand(
                        SCOPE_A, "req-delete", "memory-1", "f".repeat(64), NOW)))
        .isInstanceOf(JavaMemoryRepositoryException.class)
        .extracting(exception -> ((JavaMemoryRepositoryException) exception).failure())
        .isEqualTo(JavaMemoryRepositoryFailure.OPERATION_FAILED);
    assertThat(store.list(SCOPE_A, 100))
        .singleElement()
        .extracting(item -> item.content())
        .isEqualTo("keep-on-failure");
    assertThat(rawLong("SELECT COUNT(*) FROM memory_mutations WHERE operation = 'DELETE'"))
        .isZero();
  }

  @Test
  void enforcesCandidateLimitsFiltersModelAndDimensionsAndReturnsImmutableLists() {
    store.upsert(command(SCOPE_A, "req-1", "memory-1", MemoryType.NOTE, "one"));
    store.upsert(
        command(
            SCOPE_A,
            "req-2",
            "memory-2",
            MemoryType.NOTE,
            "two",
            'c',
            'd',
            VECTOR_A,
            "other-model",
            0,
            HAPPENED_AT,
            NOW.plusSeconds(1)));
    store.upsert(
        command(
            SCOPE_A,
            "req-3",
            "memory-3",
            MemoryType.NOTE,
            "three",
            'd',
            'e',
            VECTOR_A,
            "model-a",
            0,
            HAPPENED_AT,
            NOW.plusSeconds(2)));

    assertThatThrownBy(() -> store.loadCandidates(search(2)))
        .isInstanceOf(MemoryCandidateLimitExceededException.class)
        .hasMessage("Memory 候选数量超过上限");

    List<?> candidates = store.loadCandidates(search(10));
    assertThat(candidates).extracting("content").containsExactly("three", "one");
    assertThatThrownBy(() -> candidates.clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> store.list(SCOPE_A, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.list(SCOPE_A, 101)).isInstanceOf(IllegalArgumentException.class);
  }

  private MemorySearchRequest search(int maxCandidates) {
    return new MemorySearchRequest(
        SCOPE_A, VECTOR_A, "model-a", 1, 0.45, 0.2, 14.0, maxCandidates, NOW);
  }

  private MemoryWriteResult upsertAfter(CountDownLatch start, MemoryWriteCommand command)
      throws InterruptedException {
    start.await();
    return store.upsert(command);
  }

  private MemoryWriteCommand command(
      MemoryScope scope, String requestId, String itemId, MemoryType type, String content) {
    return command(
        scope,
        requestId,
        itemId,
        type,
        content,
        content.equals("same") ? '9' : 'b',
        'c',
        VECTOR_A,
        "model-a",
        0,
        HAPPENED_AT,
        NOW);
  }

  private MemoryWriteCommand command(
      MemoryScope scope,
      String requestId,
      String itemId,
      MemoryType type,
      String content,
      char contentHash,
      char argumentHash,
      EmbeddingVector vector,
      String model,
      int emotionalWeight,
      Instant happenedAt,
      Instant requestedAt) {
    return new MemoryWriteCommand(
        scope,
        requestId,
        itemId,
        type,
        content,
        Character.toString(contentHash).repeat(64),
        vector,
        model,
        emotionalWeight,
        MemorySourceKind.EXPLICIT_API,
        happenedAt,
        Character.toString(argumentHash).repeat(64),
        requestedAt);
  }

  private long rawLong(String sql) {
    try (var connection = schema.openConnection();
        var rows = connection.createStatement().executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private String rawString(String sql) {
    try (var connection = schema.openConnection();
        var rows = connection.createStatement().executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static MemoryScope scope(char value) {
    return new MemoryScope(Character.toString(value).repeat(64));
  }
}
