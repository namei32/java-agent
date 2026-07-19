package io.namei.agent.application;

import io.namei.agent.kernel.model.PendingTurnAnchorState;
import io.namei.agent.kernel.port.SessionRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Local control-plane application service for the one approved memory forget capability. */
public final class MemoryForgetControlService {
  private final PendingOperationStore operations;
  private final SessionRepository sessions;
  private final MemoryForgetRecovery recovery;
  private final Clock clock;

  public MemoryForgetControlService(
      PendingOperationStore operations, SessionRepository sessions, MemoryForgetRecovery recovery) {
    this(operations, sessions, recovery, Clock.systemUTC());
  }

  public MemoryForgetControlService(
      PendingOperationStore operations,
      SessionRepository sessions,
      MemoryForgetRecovery recovery,
      Clock clock) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.recovery = Objects.requireNonNull(recovery, "recovery");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Optional<PendingOperationControlStatus> status(PendingOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    return operations.find(reference).map(PendingOperationControlStatus::from);
  }

  public PendingOperationControlOutcome resume(PendingOperationReference reference) {
    PendingOperation operation =
        operations.find(Objects.requireNonNull(reference, "reference")).orElse(null);
    if (operation == null) {
      return PendingOperationControlOutcome.NOT_FOUND;
    }
    if (operation.state() == PendingOperationState.UNKNOWN) {
      return PendingOperationControlOutcome.UNKNOWN_REQUIRES_OPERATOR;
    }
    if (operation.isTerminal()) {
      return PendingOperationControlOutcome.NOT_RESUMABLE;
    }
    return switch (recovery.resume(reference)) {
      case COMMITTED -> PendingOperationControlOutcome.RESUMED;
      case UNKNOWN -> PendingOperationControlOutcome.UNKNOWN_REQUIRES_OPERATOR;
      case COMMIT_UNREPORTED, NOT_STARTED -> PendingOperationControlOutcome.NOT_RESUMABLE;
    };
  }

  public PendingOperationControlOutcome cancel(PendingOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    PendingOperationCancelStatus cancelled =
        operations.cancelIfUnconsumed(reference, clock.instant());
    return switch (cancelled) {
      case NOT_FOUND -> PendingOperationControlOutcome.NOT_FOUND;
      case NOT_CANCELLABLE -> PendingOperationControlOutcome.NOT_CANCELLABLE;
      case ALREADY_TERMINAL -> PendingOperationControlOutcome.ALREADY_TERMINAL;
      case ALREADY_CANCELLED -> PendingOperationControlOutcome.CANCELLED;
      case CANCELLED -> {
        sessions
            .findPendingTurnAnchor(reference.value())
            .filter(anchor -> anchor.state() == PendingTurnAnchorState.PENDING_APPROVAL)
            .ifPresent(sessions::cancelPendingTurnAnchorIfMatches);
        yield PendingOperationControlOutcome.CANCELLED;
      }
    };
  }
}
