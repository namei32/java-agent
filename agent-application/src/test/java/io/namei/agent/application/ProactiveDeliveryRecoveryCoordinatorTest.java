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

class ProactiveDeliveryRecoveryCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void approvedOperationInvokesFakePortExactlyOnceAndCommitsAnchor() {
    Scenario scenario = Scenario.approved();

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.COMMITTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveDeliveryOperation.State.SUCCEEDED);
    assertThat(scenario.store.operation.anchor().state())
        .isEqualTo(ProactiveDeliveryAnchor.State.COMMITTED);
    assertThat(scenario.store.receipts)
        .containsExactly(new ProactiveDeliverySafeReceipt("FAKE_ACCEPTED"));
  }

  @Test
  void unapprovedCancelledExpiredAndCipherMismatchNeverInvokeFakePort() {
    Scenario unapproved = Scenario.created();
    Scenario cancelled = Scenario.approved();
    cancelled.store.cancel();
    Scenario expired = Scenario.approved();
    expired.store.expire();
    Scenario tampered = Scenario.approved();
    tampered.store.tamperCapsule();

    assertThat(unapproved.coordinator().resume(unapproved.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(cancelled.coordinator().resume(cancelled.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(expired.coordinator().resume(expired.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(tampered.coordinator().resume(tampered.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(unapproved.port.calls).isZero();
    assertThat(cancelled.port.calls).isZero();
    assertThat(expired.port.calls).isZero();
    assertThat(tampered.port.calls).isZero();
  }

  @Test
  void uncertainFakePortResultIsUnknownAndNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.port.failure = new IllegalStateException("fake uncertain");

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveDeliveryOperation.State.UNKNOWN);
    assertThat(scenario.store.unknownCodes).containsExactly("PROACTIVE_DELIVERY_INVOKER_UNCERTAIN");
  }

  @Test
  void failedAnchorCommitIsCommitUnreportedAndNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.store.commitAnchor = false;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.COMMIT_UNREPORTED);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveDeliveryOperation.State.COMMIT_UNREPORTED);
  }

  @Test
  void auditFailureMarksUnknownBeforeFakeInvocation() {
    Scenario scenario = Scenario.approved();
    scenario.auditFailure = true;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.UNKNOWN);

    assertThat(scenario.port.calls).isZero();
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveDeliveryOperation.State.UNKNOWN);
  }

  @Test
  void concurrentResumeHasOneReservationWinnerAndOneFakeInvocation() throws Exception {
    Scenario scenario = Scenario.approved();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<ProactiveDeliveryRecoveryCoordinator.Outcome>> tasks =
          List.of(
              () -> scenario.coordinator().resume(scenario.reference()),
              () -> scenario.coordinator().resume(scenario.reference()));

      List<ProactiveDeliveryRecoveryCoordinator.Outcome> outcomes =
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
              ProactiveDeliveryRecoveryCoordinator.Outcome.COMMITTED,
              ProactiveDeliveryRecoveryCoordinator.Outcome.NOT_STARTED);
    }
    assertThat(scenario.port.calls).isEqualTo(1);
  }

  @Test
  @Tag("failure")
  void ledgerFailureAfterFakeInvocationIsUnknownAndNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.store.failMarkSucceeded = true;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(ProactiveDeliveryRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(ProactiveDeliveryOperation.State.UNKNOWN);
    assertThat(scenario.store.unknownCodes).containsExactly("PROACTIVE_DELIVERY_LEDGER_UNCERTAIN");
  }

  private static final class Scenario {
    private final Store store;
    private final AesGcmProactiveDeliveryCapsuleCipher cipher;
    private final Port port;
    private boolean auditFailure;

    private Scenario(
        Store store, AesGcmProactiveDeliveryCapsuleCipher cipher, Port port, boolean auditFailure) {
      this.store = store;
      this.cipher = cipher;
      this.port = port;
      this.auditFailure = auditFailure;
    }

    static Scenario created() {
      var store = new Store();
      var cipher =
          new AesGcmProactiveDeliveryCapsuleCipher(
              new SecretKeySpec(new byte[32], "AES"),
              "fixture-key",
              new SecureRandom(new byte[] {3}));
      var producer =
          new ProactiveDeliveryPendingProducer(
              store,
              () -> ProactiveDeliveryOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
              () -> ApprovalInboxReference.of("BBBBBBBBBBBBBBBBBBBBBB"),
              new FixedIds(),
              cipher,
              FakeProactiveRecipientReference.of("fake-recipient-fixture"),
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

    ProactiveDeliveryOperationReference reference() {
      return store.operation.reference();
    }

    ProactiveDeliveryRecoveryCoordinator coordinator() {
      ProactiveAudit audit =
          event -> {
            if (auditFailure) {
              throw new IllegalStateException("audit failure");
            }
          };
      return new ProactiveDeliveryRecoveryCoordinator(store, cipher, port, audit, CLOCK);
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
                ProactiveSourceKind.FIXED_LOCAL, "fixture-alert", "private source body"),
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

  private static final class Port implements ProactiveDeliveryPort {
    private int calls;
    private RuntimeException failure;

    @Override
    public ProactiveDeliverySafeReceipt deliver(ProactiveDeliveryCommand command) {
      calls++;
      if (failure != null) {
        throw failure;
      }
      return new ProactiveDeliverySafeReceipt("FAKE_ACCEPTED");
    }
  }

  private static final class Store implements ProactiveDeliveryPendingStore {
    private ProactiveDeliveryOperation operation;
    private EncryptedProactiveDeliveryCapsule capsule;
    private boolean approved;
    private boolean commitAnchor = true;
    private boolean failMarkSucceeded;
    private final List<ProactiveDeliverySafeReceipt> receipts = new ArrayList<>();
    private final List<String> unknownCodes = new ArrayList<>();

    @Override
    public synchronized ProactiveDeliveryOperation create(
        ProactiveDeliveryOperation value,
        ApprovalInboxEntry approvalEntry,
        EncryptedProactiveDeliveryCapsule valueCapsule) {
      operation = value;
      capsule = valueCapsule;
      return value;
    }

    @Override
    public synchronized Optional<ProactiveDeliveryOperation> find(
        ProactiveDeliveryOperationReference reference) {
      return operation != null && operation.reference().equals(reference)
          ? Optional.of(operation)
          : Optional.empty();
    }

    @Override
    public synchronized Optional<EncryptedProactiveDeliveryCapsule> loadEncryptedCapsule(
        ProactiveDeliveryOperationReference reference) {
      return operation != null && operation.reference().equals(reference)
          ? Optional.of(capsule)
          : Optional.empty();
    }

    @Override
    public synchronized ProactiveDeliveryReservation reserveApproved(
        ProactiveDeliveryOperationReference reference, Instant observedAt) {
      if (operation == null
          || !operation.reference().equals(reference)
          || !approved
          || operation.state() != ProactiveDeliveryOperation.State.PENDING_APPROVAL
          || !observedAt.isBefore(operation.approval().expiresAt())) {
        return ProactiveDeliveryReservation.notReservable();
      }
      operation =
          operation.transition(
              ProactiveDeliveryOperation.State.APPROVED_PENDING_RESUME, observedAt);
      operation = operation.transition(ProactiveDeliveryOperation.State.CONSUMING, observedAt);
      return ProactiveDeliveryReservation.reserved(operation);
    }

    @Override
    public synchronized void markSucceeded(
        ProactiveDeliveryOperationReference reference,
        ProactiveDeliverySafeReceipt receipt,
        Instant observedAt) {
      if (failMarkSucceeded) {
        throw new IllegalStateException("ledger failure");
      }
      operation = operation.transition(ProactiveDeliveryOperation.State.SUCCEEDED, observedAt);
      receipts.add(receipt);
    }

    @Override
    public synchronized void markUnknown(
        ProactiveDeliveryOperationReference reference, String code, Instant observedAt) {
      operation = operation.transition(ProactiveDeliveryOperation.State.UNKNOWN, observedAt);
      unknownCodes.add(code);
    }

    @Override
    public synchronized boolean commitAnchor(
        ProactiveDeliveryOperationReference reference, Instant observedAt) {
      if (!commitAnchor) {
        return false;
      }
      operation =
          operation.withAnchor(
              operation.anchor().transition(ProactiveDeliveryAnchor.State.COMMITTED));
      return true;
    }

    @Override
    public synchronized void markCommitUnreported(
        ProactiveDeliveryOperationReference reference, Instant observedAt) {
      operation =
          operation.transition(ProactiveDeliveryOperation.State.COMMIT_UNREPORTED, observedAt);
    }

    synchronized void cancel() {
      operation = operation.transition(ProactiveDeliveryOperation.State.CANCELLED, NOW);
    }

    synchronized void expire() {
      operation =
          operation.transition(
              ProactiveDeliveryOperation.State.EXPIRED, operation.approval().expiresAt());
    }

    synchronized void tamperCapsule() {
      byte[] ciphertext = capsule.ciphertext();
      ciphertext[0] ^= 1;
      capsule = new EncryptedProactiveDeliveryCapsule(capsule.keyId(), capsule.nonce(), ciphertext);
    }
  }
}
