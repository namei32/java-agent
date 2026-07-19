package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ApprovalInboxDecision;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.application.IdGenerator;
import io.namei.agent.application.MemoryForgetCapability;
import io.namei.agent.application.MemoryForgetControlService;
import io.namei.agent.application.MemoryForgetPendingOutcome;
import io.namei.agent.application.MemoryForgetPendingRequest;
import io.namei.agent.application.MemoryForgetPendingService;
import io.namei.agent.application.MemoryForgetRecoveryCoordinator;
import io.namei.agent.application.PendingOperationControlOutcome;
import io.namei.agent.application.PendingOperationKey;
import io.namei.agent.application.PendingOperationKeyProvider;
import io.namei.agent.application.PendingOperationReference;
import io.namei.agent.application.PendingOperationState;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolCall;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryForgetRecoveryIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String SESSION = "session-java-memory-001";
  private static final String OPERATION_REF = "AAAAAAAAAAAAAAAAAAAAAA";
  private static final MemoryScope SCOPE =
      new MemoryScope("971db4818fc2a938f4d66a981618ddd5e5c2e094d28e1a4eb669ac7c863cc02e");

  @TempDir Path tempDir;

  @Test
  void createsThenCommitsOneApprovedScopeBoundForgetAndNeverReplaysIt() {
    JdbcJavaMemoryStore memory = memoryStore();
    memory.upsert(seedMemory());

    ApprovalInboxSchemaInitializer inboxSchema =
        new ApprovalInboxSchemaInitializer(tempDir.resolve("approval-inbox.db"), 5_000);
    inboxSchema.initialize();
    JdbcPendingOperationStore operations =
        new JdbcPendingOperationStore(
            inboxSchema, new AesGcmPendingOperationCapsuleCipher(keyProvider()));
    SqliteSchemaInitializer sessionSchema =
        new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    sessionSchema.initialize();
    JdbcSessionRepository sessions = new JdbcSessionRepository(sessionSchema);
    MemoryForgetPendingService pendingService =
        new MemoryForgetPendingService(
            operations,
            sessions,
            () -> PendingOperationReference.of(OPERATION_REF),
            () -> ApprovalInboxReference.of("AQEBAQEBAQEBAQEBAQEBAQ"),
            new FixedIds(),
            CLOCK,
            Duration.ofMinutes(5));
    MemoryForgetPendingOutcome.Pending pending =
        (MemoryForgetPendingOutcome.Pending)
            pendingService.create(
                new MemoryForgetPendingRequest(
                    SESSION,
                    0,
                    "turn-id",
                    new ToolCall(
                        "call-id",
                        "forget_memory",
                        Map.of("ids", List.of(" memory-a ", "missing", "memory-a"))),
                    pendingTurn()));

    new JdbcApprovalInbox(inboxSchema)
        .resolve(
            pending.approvalReference(), ApprovalInboxDecision.APPROVED, "local-operator", NOW);

    MemoryForgetRecoveryCoordinator coordinator =
        new MemoryForgetRecoveryCoordinator(
            operations, sessions, new MemoryForgetCapability(memory, CLOCK), CLOCK);

    assertThat(coordinator.resume(pending.reference()))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.COMMITTED);
    assertThat(memory.list(SCOPE, 100)).isEmpty();
    assertThat(memory.candidateCount(SCOPE)).isZero();
    assertThat(operations.find(pending.reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.SUCCEEDED));
    assertThat(operations.findLedger(pending.reference()))
        .hasValueSatisfying(
            value -> {
              assertThat(value.state()).isEqualTo(SideEffectExecutionState.SUCCEEDED);
              assertThat(value.safeResult())
                  .hasValueSatisfying(
                      result ->
                          assertThat(result.content())
                              .isEqualTo(
                                  "{\"requested_ids\":[\"memory-a\",\"missing\"],"
                                      + "\"superseded_ids\":[\"memory-a\"],"
                                      + "\"missing_ids\":[\"missing\"],\"count\":1}"));
            });
    assertThat(sessions.load(SESSION).messages())
        .extracting(ChatMessage::content)
        .contains("已完成获批的记忆遗忘操作。");

    assertThat(coordinator.resume(pending.reference()))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(memory.list(SCOPE, 100)).isEmpty();
  }

  @Test
  void concurrentResumeAndCancelLeaveOneTerminalPathAndNeverDoubleExecute() throws Exception {
    JdbcJavaMemoryStore memory = memoryStore();
    memory.upsert(seedMemory());
    ApprovalInboxSchemaInitializer inboxSchema =
        new ApprovalInboxSchemaInitializer(tempDir.resolve("race/approval-inbox.db"), 5_000);
    inboxSchema.initialize();
    JdbcPendingOperationStore operations =
        new JdbcPendingOperationStore(
            inboxSchema, new AesGcmPendingOperationCapsuleCipher(keyProvider()));
    SqliteSchemaInitializer sessionSchema =
        new SqliteSchemaInitializer(tempDir.resolve("race/sessions.db"), 5_000);
    sessionSchema.initialize();
    JdbcSessionRepository sessions = new JdbcSessionRepository(sessionSchema);
    MemoryForgetPendingOutcome.Pending pending =
        (MemoryForgetPendingOutcome.Pending)
            new MemoryForgetPendingService(
                    operations,
                    sessions,
                    () -> PendingOperationReference.of(OPERATION_REF),
                    () -> ApprovalInboxReference.of("AQEBAQEBAQEBAQEBAQEBAQ"),
                    new FixedIds(),
                    CLOCK,
                    Duration.ofMinutes(5))
                .create(
                    new MemoryForgetPendingRequest(
                        SESSION,
                        0,
                        "turn-id",
                        new ToolCall(
                            "call-id", "forget_memory", Map.of("ids", List.of("memory-a"))),
                        pendingTurn()));
    new JdbcApprovalInbox(inboxSchema)
        .resolve(
            pending.approvalReference(), ApprovalInboxDecision.APPROVED, "local-operator", NOW);
    MemoryForgetControlService control =
        new MemoryForgetControlService(
            operations,
            sessions,
            new MemoryForgetRecoveryCoordinator(
                operations, sessions, new MemoryForgetCapability(memory, CLOCK), CLOCK),
            CLOCK);
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var resume =
          executor.submit(
              () -> {
                start.await();
                return control.resume(pending.reference());
              });
      var cancel =
          executor.submit(
              () -> {
                start.await();
                return control.cancel(pending.reference());
              });

      PendingOperationControlOutcome resumeOutcome = resume.get(10, TimeUnit.SECONDS);
      PendingOperationControlOutcome cancelOutcome = cancel.get(10, TimeUnit.SECONDS);
      if (resumeOutcome == PendingOperationControlOutcome.RESUMED) {
        assertThat(cancelOutcome)
            .isIn(
                PendingOperationControlOutcome.NOT_CANCELLABLE,
                PendingOperationControlOutcome.ALREADY_TERMINAL);
        assertThat(operations.find(pending.reference()))
            .hasValueSatisfying(
                value -> assertThat(value.state()).isEqualTo(PendingOperationState.SUCCEEDED));
        assertThat(operations.findLedger(pending.reference()))
            .hasValueSatisfying(
                value -> assertThat(value.state()).isEqualTo(SideEffectExecutionState.SUCCEEDED));
        assertThat(memory.list(SCOPE, 100)).isEmpty();
      } else {
        assertThat(resumeOutcome).isEqualTo(PendingOperationControlOutcome.NOT_RESUMABLE);
        assertThat(cancelOutcome).isEqualTo(PendingOperationControlOutcome.CANCELLED);
        assertThat(operations.find(pending.reference()))
            .hasValueSatisfying(
                value -> assertThat(value.state()).isEqualTo(PendingOperationState.CANCELLED));
        assertThat(operations.findLedger(pending.reference())).isEmpty();
        assertThat(memory.list(SCOPE, 100)).hasSize(1);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private JdbcJavaMemoryStore memoryStore() {
    JavaMemorySchemaInitializer schema =
        new JavaMemorySchemaInitializer(tempDir.resolve("memory/agent-memory.db"), 5_000);
    schema.initialize();
    return new JdbcJavaMemoryStore(schema, new Float32VectorCodec());
  }

  private static MemoryWriteCommand seedMemory() {
    return new MemoryWriteCommand(
        SCOPE,
        "seed-memory-a",
        "memory-a",
        MemoryType.NOTE,
        "local-only memory content",
        "a".repeat(64),
        new EmbeddingVector(new float[] {1.0f, 0.0f}),
        "test-model",
        0,
        MemorySourceKind.EXPLICIT_API,
        NOW,
        "b".repeat(64),
        NOW);
  }

  private static PersistedTurn pendingTurn() {
    OffsetDateTime at = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    return new PersistedTurn(
        new ChatMessage(MessageRole.USER, "遗忘记忆"),
        at,
        new ChatMessage(MessageRole.ASSISTANT, "该操作等待审批。"),
        at.plusSeconds(1));
  }

  private static PendingOperationKeyProvider keyProvider() {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) 1);
    PendingOperationKey key =
        new PendingOperationKey("memory-forget-test-key", new SecretKeySpec(bytes, "AES"));
    return new PendingOperationKeyProvider() {
      @Override
      public PendingOperationKey current() {
        return key;
      }

      @Override
      public Optional<PendingOperationKey> findByKeyId(String keyId) {
        return key.keyId().equals(keyId) ? Optional.of(key) : Optional.empty();
      }
    };
  }

  private static final class FixedIds implements IdGenerator {
    @Override
    public String newTurnId() {
      return "unused-turn-id";
    }

    @Override
    public String newApprovalId() {
      return "approval-id";
    }

    @Override
    public String newIdempotencyKey() {
      return "idempotency-key";
    }
  }
}
