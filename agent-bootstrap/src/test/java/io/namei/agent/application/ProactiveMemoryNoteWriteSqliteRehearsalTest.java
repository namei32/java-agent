package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.sqlite.Float32VectorCodec;
import io.namei.agent.adapter.sqlite.JavaMemorySchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcJavaMemoryStore;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import io.namei.agent.kernel.proactive.ProactiveSourceKind;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** P6's only real DML evidence: a Java-owned SQLite database with JUnit lifetime retention. */
class ProactiveMemoryNoteWriteSqliteRehearsalTest {
  private static final Instant NOW = Instant.parse("2026-07-20T06:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @TempDir Path tempDir;
  private Path memoryDirectory;
  private Path database;
  private JavaMemorySchemaInitializer schema;
  private JdbcJavaMemoryStore store;

  @BeforeEach
  void setUp() {
    memoryDirectory = tempDir.resolve("memory");
    database = memoryDirectory.resolve("agent-memory.db");
    schema = new JavaMemorySchemaInitializer(database, 5_000);
    schema.initialize();
    store = new JdbcJavaMemoryStore(schema, new Float32VectorCodec());
  }

  @AfterEach
  void deleteTheEntireTemporaryMemoryDirectory() throws IOException {
    if (Files.exists(memoryDirectory)) {
      try (var paths = Files.walk(memoryDirectory)) {
        paths.sorted(Comparator.reverseOrder()).forEach(this::delete);
      }
    }
    assertThat(Files.exists(database)).isFalse();
    assertThat(Files.exists(memoryDirectory)).isFalse();
  }

  @Test
  void createsAndReplaysOneApprovedNoteInsideOnlyTheTemporaryJavaDatabase() throws Exception {
    Rehearsal rehearsal = rehearsal("AAAAAAAAAAAAAAAAAAAAAA", "memory-0001");

    assertThat(rehearsal.capability.write(rehearsal.operation, rehearsal.capsule).code())
        .isEqualTo("CREATED");
    assertThat(rehearsal.capability.write(rehearsal.operation, rehearsal.capsule).code())
        .isEqualTo("CREATED");

    assertThat(database).isEqualTo(tempDir.resolve("memory/agent-memory.db"));
    assertThat(rehearsal.embeddingCalls).hasValue(1);
    assertThat(store.list(scope(), 100))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.sourceKind().name()).isEqualTo("PROACTIVE_APPROVED");
              assertThat(item.type().name()).isEqualTo("NOTE");
              assertThat(item.emotionalWeight()).isZero();
              assertThat(item.reinforcement()).isEqualTo(1);
            });
    assertThat(store.findMutation(new MemoryMutationKey(scope(), "p6-note-AAAAAAAAAAAAAAAAAAAAAA")))
        .isPresent();
  }

  @Test
  void reinforcesSameApprovedNoteUnderAnotherOperationWithoutAddingAnotherItem() throws Exception {
    Rehearsal first = rehearsal("AAAAAAAAAAAAAAAAAAAAAA", "memory-0001");
    Rehearsal second = rehearsal("BBBBBBBBBBBBBBBBBBBBBB", "memory-0002");

    assertThat(first.capability.write(first.operation, first.capsule).code()).isEqualTo("CREATED");
    assertThat(second.capability.write(second.operation, second.capsule).code())
        .isEqualTo("REINFORCED");

    assertThat(store.list(scope(), 100))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.reinforcement()).isEqualTo(2);
              assertThat(item.revision()).isEqualTo(2);
              assertThat(item.sourceKind().name()).isEqualTo("PROACTIVE_APPROVED");
            });
    assertThat(store.findMutation(new MemoryMutationKey(scope(), "p6-note-AAAAAAAAAAAAAAAAAAAAAA")))
        .isPresent();
    assertThat(store.findMutation(new MemoryMutationKey(scope(), "p6-note-BBBBBBBBBBBBBBBBBBBBBB")))
        .isPresent();
  }

  private Rehearsal rehearsal(String referenceValue, String itemId) {
    var reference = ProactiveMemoryNoteWriteOperationReference.of(referenceValue);
    var source =
        ProactiveSourceItem.fixedLocal(
            ProactiveSourceKind.FIXED_LOCAL, "fixture-memory", "private source body");
    Instant happenedAt = NOW.minusSeconds(60);
    var approval =
        new ApprovalRequest(
            "approval-" + itemId,
            "b".repeat(64),
            "turn-" + itemId,
            "call-" + itemId,
            ProactiveMemoryNoteWriteCapability.CAPABILITY_NAME,
            ProactiveMemoryNoteWriteCapability.CAPABILITY_VERSION,
            ToolRisk.WRITE,
            ApprovalFingerprint.argumentsHash(
                ProactiveMemoryNoteWriteCapsule.argumentsFor(
                    source, "daily-summary", "a".repeat(64), happenedAt)),
            "idempotency-" + itemId,
            "请求保存本地主动记忆候选。",
            NOW,
            NOW.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "d".repeat(64));
    var pending =
        ProactiveMemoryNoteWriteOperation.pending(
            reference,
            approval,
            ProactiveMemoryNoteWriteAnchor.pending(
                reference, ProactiveJobRef.parse("daily-summary"), "a".repeat(64)),
            NOW);
    var operation =
        pending
            .transition(
                ProactiveMemoryNoteWriteOperation.State.APPROVED_PENDING_RESUME, NOW.plusSeconds(1))
            .transition(ProactiveMemoryNoteWriteOperation.State.CONSUMING, NOW.plusSeconds(2));
    var embeddingCalls = new java.util.concurrent.atomic.AtomicInteger();
    var capability =
        new ProactiveMemoryNoteWriteCapability(
            request -> {
              embeddingCalls.incrementAndGet();
              return new EmbeddingResult(
                  ProactiveMemoryNoteWriteCapability.FIXED_EMBEDDING_MODEL,
                  2,
                  List.of(new EmbeddingVector(new float[] {1.0f, 0.0f})));
            },
            store,
            () -> itemId,
            CLOCK);
    var capsule = ProactiveMemoryNoteWriteCapsule.forOperation(operation, source, happenedAt);
    return new Rehearsal(capability, operation, capsule, embeddingCalls);
  }

  private io.namei.agent.kernel.memory.MemoryScope scope() {
    return ProactiveMemoryNoteWriteScope.derive(
        ProactiveJobRef.parse("daily-summary"), "a".repeat(64));
  }

  private void delete(Path path) {
    try {
      Files.delete(path);
    } catch (IOException exception) {
      throw new IllegalStateException("P6 临时 SQLite 清理失败", exception);
    }
  }

  private record Rehearsal(
      ProactiveMemoryNoteWriteCapability capability,
      ProactiveMemoryNoteWriteOperation operation,
      ProactiveMemoryNoteWriteCapsule capsule,
      java.util.concurrent.atomic.AtomicInteger embeddingCalls) {}
}
