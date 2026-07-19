package io.namei.agent.application;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnAnchorState;
import io.namei.agent.kernel.model.PendingTurnResolution;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Explicit recovery path for the one approved memory forget capability, not a generic Tool executor
 * and not a background worker.
 */
public final class MemoryForgetRecoveryCoordinator {
  private static final String UNKNOWN_INVOKER = "MEMORY_FORGET_INVOKER_UNCERTAIN";
  private static final String UNKNOWN_RESULT = "MEMORY_FORGET_INVALID_SAFE_RESULT";

  private final PendingOperationStore operations;
  private final SessionRepository sessions;
  private final MemoryForgetCapability capability;
  private final Clock clock;

  public MemoryForgetRecoveryCoordinator(
      PendingOperationStore operations,
      SessionRepository sessions,
      MemoryForgetCapability capability,
      Clock clock) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.capability = Objects.requireNonNull(capability, "capability");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Outcome resume(PendingOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    PendingTurnAnchor anchor =
        sessions
            .findPendingTurnAnchor(reference.value())
            .filter(MemoryForgetRecoveryCoordinator::isPending)
            .orElse(null);
    if (anchor == null) {
      return Outcome.NOT_STARTED;
    }
    PendingOperation operation = operations.find(reference).orElse(null);
    PendingOperationCapsule capsule = operations.loadVerifiedCapsule(reference).orElse(null);
    if (!isBoundToAnchor(operation, capsule, anchor)) {
      return Outcome.NOT_STARTED;
    }

    Instant observedAt = clock.instant();
    PendingOperationReservation reservation = operations.reserveApproved(reference, observedAt);
    if (!reservation.acquired()) {
      return Outcome.NOT_STARTED;
    }
    PendingOperation reservedOperation = reservation.operation().orElseThrow();
    if (!capability.accepts(reservedOperation, capsule)) {
      operations.markUnknown(reference, UNKNOWN_INVOKER, observedAt);
      return Outcome.UNKNOWN;
    }
    operations.markRunning(reference, observedAt);

    ToolResult safeResult;
    try {
      safeResult = capability.invoke(reservedOperation, capsule);
    } catch (RuntimeException exception) {
      operations.markUnknown(reference, UNKNOWN_INVOKER, clock.instant());
      return Outcome.UNKNOWN;
    }
    if (safeResult.status() != ToolResultStatus.SUCCESS) {
      operations.markUnknown(reference, UNKNOWN_RESULT, clock.instant());
      return Outcome.UNKNOWN;
    }
    operations.markSucceeded(reference, safeResult, clock.instant());

    try {
      if (sessions.appendPendingResolutionIfAnchorMatches(anchor, resolution())) {
        return Outcome.COMMITTED;
      }
    } catch (RuntimeException ignored) {
      // The durable side effect and its safe result were already committed; never replay it.
    }
    operations.markCommitUnreported(reference, clock.instant());
    return Outcome.COMMIT_UNREPORTED;
  }

  private boolean isBoundToAnchor(
      PendingOperation operation, PendingOperationCapsule capsule, PendingTurnAnchor anchor) {
    return operation != null
        && capsule != null
        && operation.expectedNextSequence() == anchor.resumeNextSequence()
        && capsule.sessionId().equals(anchor.sessionId())
        && capability.accepts(operation, capsule);
  }

  private static boolean isPending(PendingTurnAnchor anchor) {
    return anchor.state() == PendingTurnAnchorState.PENDING_APPROVAL;
  }

  private PendingTurnResolution resolution() {
    return new PendingTurnResolution(
        MemoryForgetCapability.PROJECTION_VERSION,
        new ChatMessage(MessageRole.ASSISTANT, MemoryForgetCapability.SAFE_COMPLETION_PROJECTION),
        OffsetDateTime.ofInstant(clock.instant(), clock.getZone()));
  }

  public enum Outcome {
    COMMITTED,
    UNKNOWN,
    COMMIT_UNREPORTED,
    NOT_STARTED
  }
}
