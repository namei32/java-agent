package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Creates a single pending {@code forget_memory} operation without registering a Tool or invoking
 * the capability.
 *
 * <p>The operation store commits first because it owns Approval and the encrypted Capsule. The
 * separate Session Pending CAS follows; when it does not commit, the operation is terminally staled
 * and can never receive an execution right.
 */
public final class MemoryForgetPendingService {
  private static final String APPROVAL_SUMMARY = "请求在当前会话范围内遗忘记忆条目。";

  private final PendingOperationStore operations;
  private final SessionRepository sessions;
  private final PendingOperationReferenceGenerator operationReferences;
  private final ApprovalInboxReferenceGenerator approvalReferences;
  private final IdGenerator ids;
  private final Clock clock;
  private final Duration approvalTimeout;

  public MemoryForgetPendingService(
      PendingOperationStore operations,
      SessionRepository sessions,
      PendingOperationReferenceGenerator operationReferences,
      ApprovalInboxReferenceGenerator approvalReferences,
      IdGenerator ids,
      Clock clock,
      Duration approvalTimeout) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.operationReferences = Objects.requireNonNull(operationReferences, "operationReferences");
    this.approvalReferences = Objects.requireNonNull(approvalReferences, "approvalReferences");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout");
    if (approvalTimeout.isNegative() || approvalTimeout.isZero()) {
      throw new IllegalArgumentException("审批有效期必须大于零");
    }
  }

  public MemoryForgetPendingOutcome create(MemoryForgetPendingRequest request) {
    Objects.requireNonNull(request, "request");
    if (!MemoryForgetCapability.TOOL_NAME.equals(request.call().name())) {
      throw new IllegalArgumentException("不是获批的 Memory Forget Tool");
    }
    List<String> normalizedIds =
        MemoryForgetCapability.normalizeArguments(request.call().arguments());
    MemoryForgetCapability.requireSafeResultBudget(normalizedIds);
    if (normalizedIds.isEmpty()) {
      return new MemoryForgetPendingOutcome.ImmediateSuccess(
          MemoryForgetCapability.immediateSuccess());
    }

    Instant issuedAt = clock.instant();
    Instant expiresAt = expiresAt(issuedAt);
    String canonicalArguments = MemoryForgetCapability.canonicalArgumentsJson(normalizedIds);
    PendingOperationReference operationReference = operationReferences.next();
    String approvalId = ids.newApprovalId();
    String idempotencyKey = ids.newIdempotencyKey();
    String sessionBinding = ApprovalFingerprint.sessionBinding(request.sessionId());
    String argumentsHash = ApprovalFingerprint.argumentsHashJson(canonicalArguments);
    String fingerprint =
        ApprovalFingerprint.calculate(
            sessionBinding,
            request.turnId(),
            request.call().id(),
            MemoryForgetCapability.TOOL_NAME,
            MemoryForgetCapability.TOOL_VERSION,
            ToolRisk.WRITE,
            argumentsHash,
            idempotencyKey,
            issuedAt,
            expiresAt);
    ApprovalRequest approval =
        new ApprovalRequest(
            approvalId,
            sessionBinding,
            request.turnId(),
            request.call().id(),
            MemoryForgetCapability.TOOL_NAME,
            MemoryForgetCapability.TOOL_VERSION,
            ToolRisk.WRITE,
            argumentsHash,
            idempotencyKey,
            APPROVAL_SUMMARY,
            issuedAt,
            expiresAt,
            ApprovalRequest.FINGERPRINT_VERSION,
            fingerprint);
    PendingOperation operation =
        PendingOperation.pending(
            operationReference, approval, pendingResumeSequence(request), issuedAt);
    PendingOperationCapsule capsule =
        PendingOperationCapsule.forOperation(
            operation,
            request.sessionId(),
            canonicalArguments,
            MemoryForgetCapability.EXECUTION_BOUNDARY_VERSION);
    ApprovalInboxReference approvalReference = approvalReferences.next();
    PendingTurnAnchor anchor =
        PendingTurnAnchor.pending(
            operationReference.value(),
            request.sessionId(),
            request.expectedNextSequence(),
            MemoryForgetCapability.PROJECTION_VERSION);

    operations.create(operation, ApprovalInboxEntry.pending(approvalReference, approval), capsule);
    try {
      if (sessions.appendPendingTurnIfNextSequence(request.pendingTurn(), anchor)) {
        return new MemoryForgetPendingOutcome.Pending(operationReference, approvalReference);
      }
    } catch (RuntimeException failure) {
      staleOrSuppress(operationReference, failure);
      throw failure;
    }
    operations.markStaleSessionIfPending(operationReference, clock.instant());
    return new MemoryForgetPendingOutcome.StaleSession(operationReference);
  }

  private Instant expiresAt(Instant issuedAt) {
    try {
      return issuedAt.plus(approvalTimeout);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("审批到期时间超出范围", exception);
    }
  }

  private static long pendingResumeSequence(MemoryForgetPendingRequest request) {
    try {
      return Math.addExact(request.expectedNextSequence(), 2);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Pending Turn 序号已耗尽", exception);
    }
  }

  private void staleOrSuppress(PendingOperationReference reference, RuntimeException primary) {
    try {
      operations.markStaleSessionIfPending(reference, clock.instant());
    } catch (RuntimeException staleFailure) {
      primary.addSuppressed(staleFailure);
    }
  }
}
