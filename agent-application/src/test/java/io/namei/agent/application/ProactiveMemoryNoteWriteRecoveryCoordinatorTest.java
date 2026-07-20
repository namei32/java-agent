package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryDeleteCommand;
import io.namei.agent.kernel.memory.MemoryDeleteResult;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryMutation;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.memory.MemoryWriteReplayQuery;
import io.namei.agent.kernel.memory.MemoryWriteResult;
import io.namei.agent.kernel.memory.MemoryWriteStatus;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryWritePort;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import io.namei.agent.kernel.proactive.ProactiveSourceKind;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ProactiveMemoryNoteWriteRecoveryCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-20T06:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void approvedOperationWritesOnceAndCommitsItsDedicatedAnchor() {
    Scenario scenario = Scenario.approved();

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.COMMITTED);

    assertThat(scenario.writer.upsertCalls).isEqualTo(1);
    assertThat(scenario.embeddings.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveMemoryNoteWriteOperation.State.SUCCEEDED);
    assertThat(scenario.store.operation.anchor().state())
        .isEqualTo(ProactiveMemoryNoteWriteAnchor.State.COMMITTED);
    assertThat(scenario.auditEvents)
        .extracting(ProactiveAuditEvent::action)
        .containsExactly(ProactiveAuditEvent.Action.MEMORY);
  }

  @Test
  void unapprovedCancelledExpiredAndTamperedCapsuleNeverEmbedOrWrite() {
    Scenario unapproved = Scenario.created();
    Scenario cancelled = Scenario.approved();
    cancelled.store.cancel();
    Scenario expired = Scenario.approved();
    expired.store.expire();
    Scenario tampered = Scenario.approved();
    tampered.store.tamperCapsule();

    assertThat(unapproved.coordinator().resume(unapproved.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(cancelled.coordinator().resume(cancelled.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(expired.coordinator().resume(expired.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(tampered.coordinator().resume(tampered.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(
            unapproved.writer.upsertCalls
                + cancelled.writer.upsertCalls
                + expired.writer.upsertCalls)
        .isZero();
    assertThat(tampered.writer.upsertCalls).isZero();
    assertThat(unapproved.embeddings.calls + cancelled.embeddings.calls + expired.embeddings.calls)
        .isZero();
    assertThat(tampered.embeddings.calls).isZero();
  }

  @Test
  void uncertainMemoryWriteAndLedgerFailureBecomeUnknownAndNeverReplay() {
    Scenario writeFailure = Scenario.approved();
    writeFailure.writer.failure = new IllegalStateException("write uncertain");
    Scenario ledgerFailure = Scenario.approved();
    ledgerFailure.store.failMarkSucceeded = true;

    assertThat(writeFailure.coordinator().resume(writeFailure.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(writeFailure.coordinator().resume(writeFailure.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(ledgerFailure.coordinator().resume(ledgerFailure.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(ledgerFailure.coordinator().resume(ledgerFailure.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(writeFailure.writer.upsertCalls).isEqualTo(1);
    assertThat(ledgerFailure.writer.upsertCalls).isEqualTo(1);
    assertThat(writeFailure.store.operation.state())
        .isEqualTo(ProactiveMemoryNoteWriteOperation.State.UNKNOWN);
    assertThat(ledgerFailure.store.operation.state())
        .isEqualTo(ProactiveMemoryNoteWriteOperation.State.UNKNOWN);
  }

  @Test
  void failedAnchorCommitIsCommitUnreportedAndIsNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.store.commitAnchor = false;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.COMMIT_UNREPORTED);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.writer.upsertCalls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveMemoryNoteWriteOperation.State.COMMIT_UNREPORTED);
  }

  @Test
  void concurrentResumeHasOneReservationWinnerAndOneMemoryWrite() throws Exception {
    Scenario scenario = Scenario.approved();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome>> tasks =
          List.of(
              () -> scenario.coordinator().resume(scenario.reference()),
              () -> scenario.coordinator().resume(scenario.reference()));

      List<ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome> outcomes =
          executor.invokeAll(tasks).stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception exception) {
                      throw new AssertionError(exception);
                    }
                  })
              .toList();

      assertThat(outcomes)
          .containsExactlyInAnyOrder(
              ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.COMMITTED,
              ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.NOT_STARTED);
    }
    assertThat(scenario.writer.upsertCalls).isEqualTo(1);
  }

  @Test
  @Tag("failure")
  void auditFailureMarksUnknownBeforeEmbeddingOrMemoryWrite() {
    Scenario scenario = Scenario.approved();
    scenario.auditFailure = true;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryNoteWriteRecoveryCoordinator.Outcome.UNKNOWN);

    assertThat(scenario.embeddings.calls).isZero();
    assertThat(scenario.writer.upsertCalls).isZero();
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveMemoryNoteWriteOperation.State.UNKNOWN);
  }

  private static final class Scenario {
    private final Store store;
    private final AesGcmProactiveMemoryNoteWriteCapsuleCipher cipher;
    private final RecordingEmbeddings embeddings;
    private final RecordingWriter writer;
    private final List<ProactiveAuditEvent> auditEvents = new ArrayList<>();
    private boolean auditFailure;

    private Scenario(
        Store store,
        AesGcmProactiveMemoryNoteWriteCapsuleCipher cipher,
        RecordingEmbeddings embeddings,
        RecordingWriter writer) {
      this.store = store;
      this.cipher = cipher;
      this.embeddings = embeddings;
      this.writer = writer;
    }

    static Scenario created() {
      var store = new Store();
      var cipher =
          new AesGcmProactiveMemoryNoteWriteCapsuleCipher(
              new SecretKeySpec(new byte[32], "AES"),
              "fixture-key",
              new SecureRandom(new byte[] {7}));
      var producer =
          new ProactiveMemoryNoteWritePendingProducer(
              store,
              () -> ProactiveMemoryNoteWriteOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
              () -> ApprovalInboxReference.of("BBBBBBBBBBBBBBBBBBBBBB"),
              new FixedIds(),
              cipher,
              CLOCK,
              Duration.ofMinutes(5));
      producer.prepare(candidate(), new TurnCancellationSource().token());
      return new Scenario(store, cipher, new RecordingEmbeddings(), new RecordingWriter());
    }

    static Scenario approved() {
      Scenario scenario = created();
      scenario.store.approved = true;
      return scenario;
    }

    ProactiveMemoryNoteWriteOperationReference reference() {
      return store.operation.reference();
    }

    ProactiveMemoryNoteWriteRecoveryCoordinator coordinator() {
      ProactiveAudit audit =
          event -> {
            if (auditFailure) {
              throw new IllegalStateException("audit failure");
            }
            auditEvents.add(event);
          };
      return new ProactiveMemoryNoteWriteRecoveryCoordinator(
          store,
          cipher,
          new ProactiveMemoryNoteWriteCapability(embeddings, writer, () -> "memory-0001", CLOCK),
          audit,
          CLOCK);
    }
  }

  private static LocalProactiveCandidateResult candidate() {
    return LocalProactiveCandidateResult.candidateReady(
        new LocalProactiveCandidate(
            new ProactiveJobLease(
                new ScheduledJob(
                    ProactiveJobRef.parse("daily-summary"),
                    new ProactiveSchedule(ProactiveScheduleKind.AT, NOW, null),
                    "a".repeat(64),
                    "b".repeat(64),
                    ProactiveJobState.RUNNING,
                    1,
                    3),
                "proactive-local",
                NOW.plusSeconds(30),
                2),
            ProactiveSourceItem.fixedLocal(
                ProactiveSourceKind.FIXED_LOCAL, "fixture-memory", "private source body"),
            NOW));
  }

  private static final class FixedIds implements IdGenerator {
    @Override
    public String newTurnId() {
      return "turn-fixture";
    }

    @Override
    public String newApprovalId() {
      return "approval-fixture";
    }

    @Override
    public String newIdempotencyKey() {
      return "idempotency-fixture";
    }
  }

  private static final class RecordingEmbeddings implements EmbeddingPort {
    private int calls;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
      calls++;
      return new EmbeddingResult(
          ProactiveMemoryNoteWriteCapability.FIXED_EMBEDDING_MODEL,
          2,
          List.of(new EmbeddingVector(new float[] {1.0f, 0.0f})));
    }
  }

  private static final class RecordingWriter implements MemoryWritePort {
    private int upsertCalls;
    private RuntimeException failure;

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
      upsertCalls++;
      if (failure != null) {
        throw failure;
      }
      return new MemoryWriteResult(
          MemoryWriteStatus.CREATED,
          new MemoryItem(
              command.itemId(),
              command.scope(),
              command.type(),
              command.content(),
              command.contentHash(),
              command.embedding(),
              command.embeddingModel(),
              1,
              command.emotionalWeight(),
              MemorySourceKind.PROACTIVE_APPROVED,
              command.happenedAt(),
              1,
              command.requestedAt(),
              command.requestedAt()));
    }

    @Override
    public MemoryDeleteResult delete(MemoryDeleteCommand command) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class Store implements ProactiveMemoryNoteWritePendingStore {
    private ProactiveMemoryNoteWriteOperation operation;
    private EncryptedProactiveMemoryNoteWriteCapsule capsule;
    private boolean approved;
    private boolean commitAnchor = true;
    private boolean failMarkSucceeded;

    @Override
    public synchronized ProactiveMemoryNoteWriteOperation create(
        ProactiveMemoryNoteWriteOperation value,
        ApprovalInboxEntry approvalEntry,
        EncryptedProactiveMemoryNoteWriteCapsule valueCapsule) {
      operation = value;
      capsule = valueCapsule;
      return value;
    }

    @Override
    public synchronized Optional<ProactiveMemoryNoteWriteOperation> find(
        ProactiveMemoryNoteWriteOperationReference reference) {
      return operation != null && operation.reference().equals(reference)
          ? Optional.of(operation)
          : Optional.empty();
    }

    @Override
    public synchronized Optional<EncryptedProactiveMemoryNoteWriteCapsule> loadEncryptedCapsule(
        ProactiveMemoryNoteWriteOperationReference reference) {
      return operation != null && operation.reference().equals(reference)
          ? Optional.of(capsule)
          : Optional.empty();
    }

    @Override
    public synchronized ProactiveMemoryNoteWriteReservation reserveApproved(
        ProactiveMemoryNoteWriteOperationReference reference, Instant observedAt) {
      if (operation == null
          || !operation.reference().equals(reference)
          || !approved
          || operation.state() != ProactiveMemoryNoteWriteOperation.State.PENDING_APPROVAL
          || !observedAt.isBefore(operation.approval().expiresAt())) {
        return ProactiveMemoryNoteWriteReservation.notReservable();
      }
      operation =
          operation.transition(
              ProactiveMemoryNoteWriteOperation.State.APPROVED_PENDING_RESUME, observedAt);
      operation =
          operation.transition(ProactiveMemoryNoteWriteOperation.State.CONSUMING, observedAt);
      return ProactiveMemoryNoteWriteReservation.reserved(operation);
    }

    @Override
    public synchronized void markSucceeded(
        ProactiveMemoryNoteWriteOperationReference reference,
        ProactiveMemoryNoteWriteSafeReceipt receipt,
        Instant observedAt) {
      if (failMarkSucceeded) {
        throw new IllegalStateException("ledger failure");
      }
      operation =
          operation.transition(ProactiveMemoryNoteWriteOperation.State.SUCCEEDED, observedAt);
    }

    @Override
    public synchronized void markUnknown(
        ProactiveMemoryNoteWriteOperationReference reference, String code, Instant observedAt) {
      operation = operation.transition(ProactiveMemoryNoteWriteOperation.State.UNKNOWN, observedAt);
    }

    @Override
    public synchronized boolean commitAnchor(
        ProactiveMemoryNoteWriteOperationReference reference, Instant observedAt) {
      if (!commitAnchor) {
        return false;
      }
      operation =
          operation.withAnchor(
              operation.anchor().transition(ProactiveMemoryNoteWriteAnchor.State.COMMITTED));
      return true;
    }

    @Override
    public synchronized void markCommitUnreported(
        ProactiveMemoryNoteWriteOperationReference reference, Instant observedAt) {
      operation =
          operation.transition(
              ProactiveMemoryNoteWriteOperation.State.COMMIT_UNREPORTED, observedAt);
    }

    synchronized void cancel() {
      operation = operation.transition(ProactiveMemoryNoteWriteOperation.State.CANCELLED, NOW);
    }

    synchronized void expire() {
      operation =
          operation.transition(
              ProactiveMemoryNoteWriteOperation.State.EXPIRED, operation.approval().expiresAt());
    }

    synchronized void tamperCapsule() {
      byte[] ciphertext = capsule.ciphertext();
      ciphertext[0] ^= 1;
      capsule =
          new EncryptedProactiveMemoryNoteWriteCapsule(
              capsule.keyId(), capsule.nonce(), ciphertext);
    }
  }
}
