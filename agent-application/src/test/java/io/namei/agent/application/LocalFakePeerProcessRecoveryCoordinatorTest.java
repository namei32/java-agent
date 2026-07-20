package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.proactive.LocalFakePeerCard;
import io.namei.agent.kernel.proactive.LocalFakePeerResult;
import io.namei.agent.kernel.proactive.PeerTaskRef;
import io.namei.agent.kernel.proactive.PeerTaskState;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class LocalFakePeerProcessRecoveryCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void approvedTaskInvokesOnlyInjectedFakePortAndCommitsAnchor() {
    Scenario scenario = Scenario.approved();

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.COMMITTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(LocalFakePeerTaskOperation.State.SUCCEEDED);
    assertThat(scenario.store.operation.anchor().state())
        .isEqualTo(LocalFakePeerAnchor.State.COMMITTED);
    assertThat(scenario.store.results)
        .containsExactly(LocalFakePeerResult.terminal(PeerTaskState.SUCCEEDED, "safe result"));
  }

  @Test
  void terminalFakeResultMapsToTerminalTaskWithoutReplay() {
    Scenario scenario = Scenario.approved();
    scenario.port.result = LocalFakePeerResult.terminal(PeerTaskState.FAILED, "safe failure");

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.COMMITTED);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state()).isEqualTo(LocalFakePeerTaskOperation.State.FAILED);
  }

  @Test
  void unapprovedCancelledExpiredAndTamperedCapsuleNeverInvokeFakePort() {
    Scenario unapproved = Scenario.created();
    Scenario cancelled = Scenario.approved();
    cancelled.store.cancel();
    Scenario expired = Scenario.approved();
    expired.store.expire();
    Scenario tampered = Scenario.approved();
    tampered.store.tamperCapsule();

    assertThat(unapproved.coordinator().resume(unapproved.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(cancelled.coordinator().resume(cancelled.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(expired.coordinator().resume(expired.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);
    assertThat(tampered.coordinator().resume(tampered.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);

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
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(LocalFakePeerTaskOperation.State.UNKNOWN);
    assertThat(scenario.store.unknownCodes).containsExactly("LOCAL_FAKE_PEER_INVOKER_UNCERTAIN");
  }

  @Test
  void auditFailureMarksUnknownBeforeFakePortInvocation() {
    Scenario scenario = Scenario.approved();
    scenario.auditFailure = true;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.UNKNOWN);

    assertThat(scenario.port.calls).isZero();
    assertThat(scenario.store.operation.state())
        .isEqualTo(LocalFakePeerTaskOperation.State.UNKNOWN);
  }

  @Test
  void failedAnchorCommitIsCommitUnreportedAndNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.store.commitAnchor = false;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.COMMIT_UNREPORTED);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(LocalFakePeerTaskOperation.State.COMMIT_UNREPORTED);
  }

  @Test
  void concurrentResumeHasOneReservationWinnerAndOneFakePortInvocation() throws Exception {
    Scenario scenario = Scenario.approved();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<LocalFakePeerProcessRecoveryCoordinator.Outcome>> tasks =
          List.of(
              () -> scenario.coordinator().resume(scenario.reference()),
              () -> scenario.coordinator().resume(scenario.reference()));

      List<LocalFakePeerProcessRecoveryCoordinator.Outcome> outcomes =
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
              LocalFakePeerProcessRecoveryCoordinator.Outcome.COMMITTED,
              LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);
    }
    assertThat(scenario.port.calls).isEqualTo(1);
  }

  @Test
  void runningCancellationRequestsOnlyTheFakePortAndPreventsTerminalReplay() throws Exception {
    Scenario scenario = Scenario.approved();
    scenario.port.blockUntilReleased = true;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var resumed = executor.submit(() -> scenario.coordinator().resume(scenario.reference()));
      assertThat(scenario.port.started.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(scenario.coordinator().cancel(scenario.reference()))
          .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.CANCELLED);
      scenario.port.release.countDown();

      assertThat(resumed.get())
          .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.CANCELLED);
    }

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.port.cancelCalls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(LocalFakePeerTaskOperation.State.CANCELLED);
  }

  @Test
  @Tag("failure")
  void ledgerFailureAfterFakePortInvocationIsUnknownAndNeverReplayed() {
    Scenario scenario = Scenario.approved();
    scenario.store.failMarkTerminal = true;

    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.UNKNOWN);
    assertThat(scenario.coordinator().resume(scenario.reference()))
        .isEqualTo(LocalFakePeerProcessRecoveryCoordinator.Outcome.NOT_STARTED);

    assertThat(scenario.port.calls).isEqualTo(1);
    assertThat(scenario.store.operation.state())
        .isEqualTo(LocalFakePeerTaskOperation.State.UNKNOWN);
    assertThat(scenario.store.unknownCodes).containsExactly("LOCAL_FAKE_PEER_LEDGER_UNCERTAIN");
  }

  private static final class Scenario {
    private final Store store;
    private final AesGcmLocalFakePeerCapsuleCipher cipher;
    private final Port port;
    private boolean auditFailure;

    private Scenario(Store store, AesGcmLocalFakePeerCapsuleCipher cipher, Port port) {
      this.store = store;
      this.cipher = cipher;
      this.port = port;
    }

    static Scenario created() {
      var store = new Store();
      var cipher =
          new AesGcmLocalFakePeerCapsuleCipher(
              new SecretKeySpec(new byte[32], "AES"),
              "fixture-key",
              new SecureRandom(new byte[] {7}));
      var producer =
          new LocalFakePeerPendingProducer(
              store,
              () -> PeerTaskRef.parse("peer-task-fixture"),
              () -> ApprovalInboxReference.of("BBBBBBBBBBBBBBBBBBBBBB"),
              new FixedIds(),
              cipher,
              CLOCK,
              Duration.ofMinutes(5));
      producer.prepare(LocalFakePeerCard.approved(), new TurnCancellationSource().token());
      return new Scenario(store, cipher, new Port());
    }

    static Scenario approved() {
      Scenario scenario = created();
      scenario.store.approved = true;
      return scenario;
    }

    PeerTaskRef reference() {
      return store.operation.reference();
    }

    LocalFakePeerProcessRecoveryCoordinator coordinator() {
      ProactiveAudit audit =
          event -> {
            if (auditFailure) {
              throw new IllegalStateException("audit failure");
            }
          };
      return new LocalFakePeerProcessRecoveryCoordinator(store, cipher, port, audit, CLOCK);
    }
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

  private static final class Port implements FakePeerProcessPort {
    private int calls;
    private int cancelCalls;
    private RuntimeException failure;
    private LocalFakePeerResult result =
        LocalFakePeerResult.terminal(PeerTaskState.SUCCEEDED, "safe result");
    private boolean blockUntilReleased;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public LocalFakePeerResult execute(LocalFakePeerProcessCommand command) {
      calls++;
      started.countDown();
      if (failure != null) {
        throw failure;
      }
      if (blockUntilReleased) {
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("fake port interrupted", interrupted);
        }
      }
      return result;
    }

    @Override
    public void cancel(PeerTaskRef reference) {
      cancelCalls++;
    }
  }

  private static final class Store implements LocalFakePeerPendingStore {
    private LocalFakePeerTaskOperation operation;
    private EncryptedLocalFakePeerCapsule capsule;
    private boolean approved;
    private boolean commitAnchor = true;
    private boolean failMarkTerminal;
    private final List<LocalFakePeerResult> results = new ArrayList<>();
    private final List<String> unknownCodes = new ArrayList<>();

    @Override
    public synchronized LocalFakePeerTaskOperation create(
        LocalFakePeerTaskOperation value,
        ApprovalInboxEntry approvalEntry,
        EncryptedLocalFakePeerCapsule valueCapsule) {
      operation = value;
      capsule = valueCapsule;
      return value;
    }

    @Override
    public synchronized Optional<LocalFakePeerTaskOperation> find(PeerTaskRef reference) {
      return operation != null && operation.reference().equals(reference)
          ? Optional.of(operation)
          : Optional.empty();
    }

    @Override
    public synchronized Optional<EncryptedLocalFakePeerCapsule> loadEncryptedCapsule(
        PeerTaskRef reference) {
      return operation != null && operation.reference().equals(reference)
          ? Optional.of(capsule)
          : Optional.empty();
    }

    @Override
    public synchronized LocalFakePeerReservation reserveApproved(
        PeerTaskRef reference, Instant observedAt) {
      if (operation == null
          || !operation.reference().equals(reference)
          || !approved
          || operation.state() != LocalFakePeerTaskOperation.State.PENDING_APPROVAL
          || !observedAt.isBefore(operation.approval().expiresAt())) {
        return LocalFakePeerReservation.notReservable();
      }
      operation =
          operation.transition(
              LocalFakePeerTaskOperation.State.APPROVED_PENDING_RESUME, observedAt);
      operation = operation.transition(LocalFakePeerTaskOperation.State.RUNNING, observedAt);
      return LocalFakePeerReservation.reserved(operation);
    }

    @Override
    public synchronized boolean cancelRunning(PeerTaskRef reference, Instant observedAt) {
      if (operation == null
          || !operation.reference().equals(reference)
          || operation.state() != LocalFakePeerTaskOperation.State.RUNNING) {
        return false;
      }
      operation = operation.transition(LocalFakePeerTaskOperation.State.CANCELLED, observedAt);
      operation =
          operation.withAnchor(operation.anchor().transition(LocalFakePeerAnchor.State.CANCELLED));
      return true;
    }

    @Override
    public synchronized void markTerminal(
        PeerTaskRef reference, LocalFakePeerResult result, Instant observedAt) {
      if (failMarkTerminal) {
        throw new IllegalStateException("ledger failure");
      }
      operation =
          operation.transition(LocalFakePeerTaskOperation.State.from(result.state()), observedAt);
      results.add(result);
    }

    @Override
    public synchronized void markUnknown(PeerTaskRef reference, String code, Instant observedAt) {
      operation = operation.transition(LocalFakePeerTaskOperation.State.UNKNOWN, observedAt);
      unknownCodes.add(code);
    }

    @Override
    public synchronized boolean commitAnchor(PeerTaskRef reference, Instant observedAt) {
      if (!commitAnchor) {
        return false;
      }
      operation =
          operation.withAnchor(operation.anchor().transition(LocalFakePeerAnchor.State.COMMITTED));
      return true;
    }

    @Override
    public synchronized void markCommitUnreported(PeerTaskRef reference, Instant observedAt) {
      operation =
          operation.transition(LocalFakePeerTaskOperation.State.COMMIT_UNREPORTED, observedAt);
    }

    synchronized void cancel() {
      operation = operation.transition(LocalFakePeerTaskOperation.State.CANCELLED, NOW);
    }

    synchronized void expire() {
      operation =
          operation.transition(
              LocalFakePeerTaskOperation.State.EXPIRED, operation.approval().expiresAt());
    }

    synchronized void tamperCapsule() {
      byte[] ciphertext = capsule.ciphertext();
      ciphertext[0] ^= 1;
      capsule = new EncryptedLocalFakePeerCapsule(capsule.keyId(), capsule.nonce(), ciphertext);
    }
  }
}
