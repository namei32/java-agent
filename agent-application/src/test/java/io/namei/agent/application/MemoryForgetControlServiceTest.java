package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnAnchorState;
import io.namei.agent.kernel.model.PendingTurnResolution;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryForgetControlServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
  private static final String REF = "AAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void statusProjectsOnlyTheVersionStateAndUpdateTime() {
    var store = new Store(operation(PendingOperationState.UNKNOWN));
    var service = service(store, new Sessions(anchor()));

    assertThat(service.status(PendingOperationReference.of(REF)))
        .contains(new PendingOperationControlStatus(1, "UNKNOWN", NOW));
    assertThat(
            java.util.Arrays.stream(PendingOperationControlStatus.class.getRecordComponents())
                .map(component -> component.getName()))
        .containsExactly("schemaVersion", "state", "updatedAt");
  }

  @Test
  void onlyDelegatesResumeForNonterminalOperationsAndMapsUnknownWithoutReplay() {
    var store = new Store(operation(PendingOperationState.PENDING_APPROVAL));
    var recovery = new CountingRecovery(MemoryForgetRecoveryCoordinator.Outcome.COMMITTED);
    var service = service(store, new Sessions(anchor()), recovery);

    assertThat(service.resume(PendingOperationReference.of(REF)))
        .isEqualTo(PendingOperationControlOutcome.RESUMED);
    assertThat(recovery.calls).isOne();

    store.operation = operation(PendingOperationState.UNKNOWN);
    assertThat(service.resume(PendingOperationReference.of(REF)))
        .isEqualTo(PendingOperationControlOutcome.UNKNOWN_REQUIRES_OPERATOR);
    assertThat(recovery.calls).isOne();
  }

  @Test
  void cancelsOnlyTheUnconsumedOperationThenItsMatchingAnchor() {
    var store = new Store(operation(PendingOperationState.PENDING_APPROVAL));
    var sessions = new Sessions(anchor());
    var service = service(store, sessions);

    assertThat(service.cancel(PendingOperationReference.of(REF)))
        .isEqualTo(PendingOperationControlOutcome.CANCELLED);
    assertThat(store.cancelCalls).isOne();
    assertThat(sessions.cancelCalls).isOne();
    assertThat(sessions.anchor.state()).isEqualTo(PendingTurnAnchorState.CANCELLED);

    store.cancelStatus = PendingOperationCancelStatus.NOT_CANCELLABLE;
    assertThat(service.cancel(PendingOperationReference.of(REF)))
        .isEqualTo(PendingOperationControlOutcome.NOT_CANCELLABLE);
    assertThat(sessions.cancelCalls).isOne();
  }

  @Test
  void anchorFailureCannotRestoreTheAlreadyCancelledExecutionRight() {
    var store = new Store(operation(PendingOperationState.PENDING_APPROVAL));
    var sessions = new Sessions(anchor());
    sessions.failFind = true;
    var recovery = new CountingRecovery(MemoryForgetRecoveryCoordinator.Outcome.COMMITTED);
    var service = service(store, sessions, recovery);

    assertThatThrownBy(() -> service.cancel(PendingOperationReference.of(REF)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.operation.state()).isEqualTo(PendingOperationState.CANCELLED);
    assertThat(service.resume(PendingOperationReference.of(REF)))
        .isEqualTo(PendingOperationControlOutcome.NOT_RESUMABLE);
    assertThat(recovery.calls).isZero();
  }

  private static MemoryForgetControlService service(Store store, Sessions sessions) {
    return service(
        store, sessions, new CountingRecovery(MemoryForgetRecoveryCoordinator.Outcome.NOT_STARTED));
  }

  private static MemoryForgetControlService service(
      Store store, Sessions sessions, MemoryForgetRecovery recovery) {
    return new MemoryForgetControlService(store, sessions, recovery);
  }

  private static PendingTurnAnchor anchor() {
    return PendingTurnAnchor.pending(REF, "session-java-memory-001", 0, "pending-projection-v1");
  }

  private static PendingOperation operation(PendingOperationState state) {
    String arguments = "{\"ids\":[\"memory-a\"]}";
    ApprovalRequest approval =
        new ApprovalRequest(
            "approval-id",
            ApprovalFingerprint.sessionBinding("session-java-memory-001"),
            "turn-id",
            "call-id",
            "forget_memory",
            "java-memory-forget-v1",
            ToolRisk.WRITE,
            ApprovalFingerprint.argumentsHashJson(arguments),
            "idempotency-key",
            "受控记忆遗忘",
            NOW,
            NOW.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64));
    return new PendingOperation(PendingOperationReference.of(REF), approval, 2, state, NOW);
  }

  private static final class CountingRecovery implements MemoryForgetRecovery {
    private final MemoryForgetRecoveryCoordinator.Outcome outcome;
    private int calls;

    private CountingRecovery(MemoryForgetRecoveryCoordinator.Outcome outcome) {
      this.outcome = outcome;
    }

    @Override
    public MemoryForgetRecoveryCoordinator.Outcome resume(PendingOperationReference reference) {
      calls++;
      return outcome;
    }
  }

  private static final class Store implements PendingOperationStore {
    private PendingOperation operation;
    private PendingOperationCancelStatus cancelStatus = PendingOperationCancelStatus.CANCELLED;
    private int cancelCalls;

    private Store(PendingOperation operation) {
      this.operation = operation;
    }

    @Override
    public PendingOperation create(
        PendingOperation value, ApprovalInboxEntry approval, PendingOperationCapsule capsule) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingOperation> find(PendingOperationReference reference) {
      return operation.reference().equals(reference) ? Optional.of(operation) : Optional.empty();
    }

    @Override
    public PendingOperationCancelStatus cancelIfUnconsumed(
        PendingOperationReference reference, Instant observedAt) {
      cancelCalls++;
      if (cancelStatus == PendingOperationCancelStatus.CANCELLED) {
        operation =
            operation.transitionTo(
                PendingOperationState.CANCELLED, operation.stateChangedAt().plusSeconds(1));
      }
      return cancelStatus;
    }

    @Override
    public PendingOperationReservation reserveApproved(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markRunning(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markSucceeded(
        PendingOperationReference reference, ToolResult safeResult, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markFailedBeforeStart(
        PendingOperationReference reference, ToolResult safeResult, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperationLedgerEntry markUnknown(
        PendingOperationReference reference, String errorCode, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PendingOperation markCommitUnreported(
        PendingOperationReference reference, Instant observedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingOperationLedgerEntry> findLedger(PendingOperationReference reference) {
      return Optional.empty();
    }
  }

  private static final class Sessions implements SessionRepository {
    private PendingTurnAnchor anchor;
    private int cancelCalls;
    private boolean failFind;

    private Sessions(PendingTurnAnchor anchor) {
      this.anchor = anchor;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, List.of(), 2);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
      if (failFind) {
        throw new IllegalStateException("session unavailable");
      }
      return Optional.of(anchor);
    }

    @Override
    public boolean cancelPendingTurnAnchorIfMatches(PendingTurnAnchor value) {
      cancelCalls++;
      if (!anchor.equals(value) || anchor.state() != PendingTurnAnchorState.PENDING_APPROVAL) {
        return false;
      }
      anchor = anchor.transitionTo(PendingTurnAnchorState.CANCELLED);
      return true;
    }

    @Override
    public boolean appendPendingResolutionIfAnchorMatches(
        PendingTurnAnchor anchor, PendingTurnResolution resolution) {
      return false;
    }
  }
}
