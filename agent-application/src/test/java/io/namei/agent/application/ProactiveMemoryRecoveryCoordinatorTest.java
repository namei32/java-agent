package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

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

class ProactiveMemoryRecoveryCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void approvedOperationInvokesFakeMemoryPortExactlyOnceAndCommitsAnchor() {
    Scenario scenario = Scenario.approved();

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.COMMITTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveMemoryOperation.State.SUCCEEDED);
    assertThat(scenario.store.operation.anchor().state())
        .isEqualTo(ProactiveMemoryAnchor.State.COMMITTED);
    assertThat(scenario.store.receipts).containsExactly(new ProactiveMemorySafeReceipt("CREATED"));
  }

  @Test
  void approvedMemoryMutationWritesAMemoryAuditActionBeforePortInvocation() {
    Scenario scenario = Scenario.approved();

    scenario.coordinator().resume(scenario.reference());

    assertThat(scenario.auditEvents)
        .extracting(ProactiveAuditEvent::action)
        .containsExactly(ProactiveAuditEvent.Action.MEMORY);
  }

  @Test
  void fakeMemoryPortReceivesOnlyDerivedScopeAndFixedEmbeddingProfile() {
    Scenario scenario = Scenario.approved();

    scenario.coordinator().resume(scenario.reference());

    assertThat(scenario.port.command.type().name()).isEqualTo("NOTE");
    assertThat(scenario.port.command.scopeHash())
        .isEqualTo(ApprovalFingerprint.sessionBinding("proactive:daily-summary:" + "a".repeat(64)));
    assertThat(scenario.port.command.embeddingProfile()).isEqualTo("fake-r14-memory-v1");
    assertThat(scenario.port.command.toString())
        .doesNotContain("daily-summary", "private source body", "a".repeat(64));
  }

  @Test
  void unapprovedCancelledExpiredAndCipherMismatchNeverInvokeFakeMemoryPort() {
    Scenario unapproved = Scenario.created();
    Scenario cancelled = Scenario.approved();
    cancelled.store.cancel();
    Scenario expired = Scenario.approved();
    expired.store.expire();
    Scenario tampered = Scenario.approved();
    tampered.store.tamperCapsule();

    assertThat(unapproved.coordinator().resume(unapproved.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(cancelled.coordinator().resume(cancelled.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(expired.coordinator().resume(expired.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(tampered.coordinator().resume(tampered.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(unapproved.port.calls).isZero();
    assertThat(cancelled.port.calls).isZero();
    assertThat(expired.port.calls).isZero();
    assertThat(tampered.port.calls).isZero();
  }

  @Test
  void uncertainFakeMemoryPortResultIsUnknownAndNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.port.failure = new IllegalStateException("fake uncertain");

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state()).isEqualTo(ProactiveMemoryOperation.State.UNKNOWN);
    assertThat(scenario.store.unknownCodes).containsExactly("PROACTIVE_MEMORY_INVOKER_UNCERTAIN");
  }

  @Test
  void failedAnchorCommitIsCommitUnreportedAndNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.store.commitAnchor = false;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.COMMIT_UNREPORTED);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveMemoryOperation.State.COMMIT_UNREPORTED);
  }

  @Test
  void auditFailureMarksUnknownBeforeFakeMemoryInvocation() {
    Scenario scenario = Scenario.approved();
    scenario.auditFailure = true;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.UNKNOWN);

    assertThat(scenario.port.calls).isZero();
    assertThat(scenario.store.operation.state()).isEqualTo(ProactiveMemoryOperation.State.UNKNOWN);
  }

  @Test
  void concurrentResumeHasOneReservationWinnerAndOneFakeMemoryInvocation() throws Exception {
    Scenario scenario = Scenario.approved();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<ProactiveMemoryRecoveryCoordinator.Outcome>> tasks =
          List.of(
              () -> scenario.coordinator().resume(scenario.reference()),
              () -> scenario.coordinator().resume(scenario.reference()));

      List<ProactiveMemoryRecoveryCoordinator.Outcome> outcomes =
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
              ProactiveMemoryRecoveryCoordinator.Outcome.COMMITTED,
              ProactiveMemoryRecoveryCoordinator.Outcome.NOT_STARTED);
    }
    assertThat(scenario.port.calls).isEqualTo(1);
  }

  @Test
  @Tag("failure")
  void ledgerFailureAfterFakeMemoryInvocationIsUnknownAndNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.store.failMarkSucceeded = true;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveMemoryRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state()).isEqualTo(ProactiveMemoryOperation.State.UNKNOWN);
    assertThat(scenario.store.unknownCodes).containsExactly("PROACTIVE_MEMORY_LEDGER_UNCERTAIN");
  }

  private static final class Scenario {
    private final Store store;
    private final AesGcmProactiveMemoryCapsuleCipher cipher;
    private final Port port;
    private final List<ProactiveAuditEvent> auditEvents = new ArrayList<>();
    private boolean auditFailure;

    private Scenario(
        Store store, AesGcmProactiveMemoryCapsuleCipher cipher, Port port, boolean auditFailure) {
      this.store = store;
      this.cipher = cipher;
      this.port = port;
      this.auditFailure = auditFailure;
    }

    static Scenario created() {
      var store = new Store();
      var cipher =
          new AesGcmProactiveMemoryCapsuleCipher(
              new SecretKeySpec(new byte[32], "AES"),
              "fixture-key",
              new SecureRandom(new byte[] {5}));
      var producer =
          new ProactiveMemoryPendingProducer(
              store,
              () -> ProactiveMemoryOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
              () -> ApprovalInboxReference.of("BBBBBBBBBBBBBBBBBBBBBB"),
              new FixedIds(),
              cipher,
              CLOCK,
              Duration.ofMinutes(5));
      producer.prepare(candidate(), new TurnCancellationSource().token());
      return new Scenario(store, cipher, new Port(), false);
    }

    static Scenario approved() {
      Scenario scenario = created();
      scenario.store.approved = true;
      return scenario;
    }

    ProactiveMemoryOperationReference reference() {
      return store.operation.reference();
    }

    ProactiveMemoryRecoveryCoordinator coordinator() {
      ProactiveAudit audit =
          event -> {
            if (auditFailure) {
              throw new IllegalStateException("audit failure");
            }
            auditEvents.add(event);
          };
      return new ProactiveMemoryRecoveryCoordinator(store, cipher, port, audit, CLOCK);
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

  private static final class Port implements ProactiveMemoryMutationPort {
    private int calls;
    private RuntimeException failure;
    private ProactiveMemoryMutationCommand command;

    @Override
    public ProactiveMemorySafeReceipt capture(ProactiveMemoryMutationCommand command) {
      calls++;
      this.command = command;
      if (failure != null) {
        throw failure;
      }
      return new ProactiveMemorySafeReceipt("CREATED");
    }
  }

  private static final class Store implements ProactiveMemoryPendingStore {
    private ProactiveMemoryOperation operation;
    private EncryptedProactiveMemoryCapsule capsule;
    private boolean approved;
    private boolean commitAnchor = true;
    private boolean failMarkSucceeded;
    private final List<ProactiveMemorySafeReceipt> receipts = new ArrayList<>();
    private final List<String> unknownCodes = new ArrayList<>();

    @Override
    public synchronized ProactiveMemoryOperation create(
        ProactiveMemoryOperation value,
        ApprovalInboxEntry approvalEntry,
        EncryptedProactiveMemoryCapsule valueCapsule) {
      operation = value;
      capsule = valueCapsule;
      return value;
    }

    @Override
    public synchronized Optional<ProactiveMemoryOperation> find(
        ProactiveMemoryOperationReference reference) {
      return operation != null && operation.reference().equals(reference)
          ? Optional.of(operation)
          : Optional.empty();
    }

    @Override
    public synchronized Optional<EncryptedProactiveMemoryCapsule> loadEncryptedCapsule(
        ProactiveMemoryOperationReference reference) {
      return operation != null && operation.reference().equals(reference)
          ? Optional.of(capsule)
          : Optional.empty();
    }

    @Override
    public synchronized ProactiveMemoryReservation reserveApproved(
        ProactiveMemoryOperationReference reference, Instant observedAt) {
      if (operation == null
          || !operation.reference().equals(reference)
          || !approved
          || operation.state() != ProactiveMemoryOperation.State.PENDING_APPROVAL
          || !observedAt.isBefore(operation.approval().expiresAt())) {
        return ProactiveMemoryReservation.notReservable();
      }
      operation =
          operation.transition(ProactiveMemoryOperation.State.APPROVED_PENDING_RESUME, observedAt);
      operation = operation.transition(ProactiveMemoryOperation.State.CONSUMING, observedAt);
      return ProactiveMemoryReservation.reserved(operation);
    }

    @Override
    public synchronized void markSucceeded(
        ProactiveMemoryOperationReference reference,
        ProactiveMemorySafeReceipt receipt,
        Instant observedAt) {
      if (failMarkSucceeded) {
        throw new IllegalStateException("ledger failure");
      }
      operation = operation.transition(ProactiveMemoryOperation.State.SUCCEEDED, observedAt);
      receipts.add(receipt);
    }

    @Override
    public synchronized void markUnknown(
        ProactiveMemoryOperationReference reference, String code, Instant observedAt) {
      operation = operation.transition(ProactiveMemoryOperation.State.UNKNOWN, observedAt);
      unknownCodes.add(code);
    }

    @Override
    public synchronized boolean commitAnchor(
        ProactiveMemoryOperationReference reference, Instant observedAt) {
      if (!commitAnchor) {
        return false;
      }
      operation =
          operation.withAnchor(
              operation.anchor().transition(ProactiveMemoryAnchor.State.COMMITTED));
      return true;
    }

    @Override
    public synchronized void markCommitUnreported(
        ProactiveMemoryOperationReference reference, Instant observedAt) {
      operation =
          operation.transition(ProactiveMemoryOperation.State.COMMIT_UNREPORTED, observedAt);
    }

    synchronized void cancel() {
      operation = operation.transition(ProactiveMemoryOperation.State.CANCELLED, NOW);
    }

    synchronized void expire() {
      operation =
          operation.transition(
              ProactiveMemoryOperation.State.EXPIRED, operation.approval().expiresAt());
    }

    synchronized void tamperCapsule() {
      byte[] ciphertext = capsule.ciphertext();
      ciphertext[0] ^= 1;
      capsule = new EncryptedProactiveMemoryCapsule(capsule.keyId(), capsule.nonce(), ciphertext);
    }
  }
}
