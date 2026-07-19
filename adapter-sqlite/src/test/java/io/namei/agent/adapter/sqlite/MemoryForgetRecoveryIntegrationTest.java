package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ApprovalFingerprint;
import io.namei.agent.application.ApprovalInboxDecision;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.application.MemoryForgetCapability;
import io.namei.agent.application.MemoryForgetRecoveryCoordinator;
import io.namei.agent.application.PendingOperation;
import io.namei.agent.application.PendingOperationCapsule;
import io.namei.agent.application.PendingOperationKey;
import io.namei.agent.application.PendingOperationKeyProvider;
import io.namei.agent.application.PendingOperationReference;
import io.namei.agent.application.PendingOperationState;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
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
  void commitsOneApprovedScopeBoundForgetThroughAuthenticatedCapsuleAndNeverReplaysIt() {
    JdbcJavaMemoryStore memory = memoryStore();
    memory.upsert(seedMemory());

    ApprovalInboxSchemaInitializer inboxSchema =
        new ApprovalInboxSchemaInitializer(tempDir.resolve("approval-inbox.db"), 5_000);
    inboxSchema.initialize();
    JdbcPendingOperationStore operations =
        new JdbcPendingOperationStore(
            inboxSchema, new AesGcmPendingOperationCapsuleCipher(keyProvider()));
    PendingOperation operation = operation();
    operations.create(operation, inbox(operation), capsule(operation));
    new JdbcApprovalInbox(inboxSchema)
        .resolve(
            inbox(operation).reference(), ApprovalInboxDecision.APPROVED, "local-operator", NOW);

    SqliteSchemaInitializer sessionSchema =
        new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    sessionSchema.initialize();
    JdbcSessionRepository sessions = new JdbcSessionRepository(sessionSchema);
    PendingTurnAnchor anchor =
        PendingTurnAnchor.pending(OPERATION_REF, SESSION, 0, "memory-forget-pending-projection-v1");
    assertThat(sessions.appendPendingTurnIfNextSequence(pendingTurn(), anchor)).isTrue();

    MemoryForgetRecoveryCoordinator coordinator =
        new MemoryForgetRecoveryCoordinator(
            operations, sessions, new MemoryForgetCapability(memory, CLOCK), CLOCK);

    assertThat(coordinator.resume(operation.reference()))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.COMMITTED);
    assertThat(memory.list(SCOPE, 100)).isEmpty();
    assertThat(memory.candidateCount(SCOPE)).isZero();
    assertThat(operations.find(operation.reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.SUCCEEDED));
    assertThat(operations.findLedger(operation.reference()))
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

    assertThat(coordinator.resume(operation.reference()))
        .isEqualTo(MemoryForgetRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(memory.list(SCOPE, 100)).isEmpty();
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

  private static PendingOperation operation() {
    String arguments = "{\"ids\":[\" memory-a \",\"missing\",\"memory-a\"]}";
    ApprovalRequest request =
        new ApprovalRequest(
            "approval-id",
            ApprovalFingerprint.sessionBinding(SESSION),
            "turn-id",
            "call-id",
            "forget_memory",
            "java-memory-forget-v1",
            ToolRisk.WRITE,
            ApprovalFingerprint.argumentsHashJson(arguments),
            "idempotency-key",
            "受控记忆遗忘",
            NOW.minusSeconds(1),
            NOW.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64));
    return PendingOperation.pending(
        PendingOperationReference.of(OPERATION_REF), request, 2, NOW.minusSeconds(1));
  }

  private static ApprovalInboxEntry inbox(PendingOperation operation) {
    return ApprovalInboxEntry.pending(
        ApprovalInboxReference.of("AQEBAQEBAQEBAQEBAQEBAQ"), operation.approval());
  }

  private static PendingOperationCapsule capsule(PendingOperation operation) {
    return PendingOperationCapsule.forOperation(
        operation,
        SESSION,
        "{\"ids\":[\" memory-a \",\"missing\",\"memory-a\"]}",
        MemoryForgetCapability.EXECUTION_BOUNDARY_VERSION);
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
}
