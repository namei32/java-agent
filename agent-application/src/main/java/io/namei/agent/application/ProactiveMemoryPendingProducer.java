package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Creates P3 pending state only; it has no Memory DML, Scheduler, Tool, or Bootstrap authority. */
final class ProactiveMemoryPendingProducer {
  static final String CAPABILITY_NAME = "proactive_memory_capture";
  static final String CAPABILITY_VERSION = "r14-proactive-memory-v1";
  private static final String APPROVAL_SUMMARY = "请求保存本地主动记忆候选。";

  private final ProactiveMemoryPendingStore store;
  private final ProactiveMemoryOperationReferenceGenerator operationReferences;
  private final ApprovalInboxReferenceGenerator approvalReferences;
  private final IdGenerator ids;
  private final ProactiveMemoryCapsuleCipher cipher;
  private final Clock clock;
  private final Duration approvalTimeout;

  ProactiveMemoryPendingProducer(
      ProactiveMemoryPendingStore store,
      ProactiveMemoryOperationReferenceGenerator operationReferences,
      ApprovalInboxReferenceGenerator approvalReferences,
      IdGenerator ids,
      ProactiveMemoryCapsuleCipher cipher,
      Clock clock,
      Duration approvalTimeout) {
    this.store = Objects.requireNonNull(store, "store");
    this.operationReferences = Objects.requireNonNull(operationReferences, "operationReferences");
    this.approvalReferences = Objects.requireNonNull(approvalReferences, "approvalReferences");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout");
    if (approvalTimeout.isNegative() || approvalTimeout.isZero()) {
      throw new IllegalArgumentException("审批有效期必须大于零");
    }
  }

  ProactiveMemoryPreparationOutcome prepare(
      LocalProactiveCandidateResult candidateResult, TurnCancellation cancellation) {
    Objects.requireNonNull(candidateResult, "candidateResult");
    Objects.requireNonNull(cancellation, "cancellation");
    if (cancellation.isCancellationRequested()) {
      return ProactiveMemoryPreparationOutcome.cancelled();
    }
    LocalProactiveCandidate candidate = candidateResult.candidateForPreparation().orElse(null);
    if (candidate == null) {
      return ProactiveMemoryPreparationOutcome.notReady();
    }
    if (!candidate.tryClaimForMemoryCapture()) {
      return ProactiveMemoryPreparationOutcome.alreadyPrepared();
    }
    try {
      if (cancellation.isCancellationRequested()) {
        candidate.releaseAfterMemoryCapturePreparationFailure();
        return ProactiveMemoryPreparationOutcome.cancelled();
      }
      Instant issuedAt = clock.instant();
      if (!candidate.lease().expiresAt().isAfter(issuedAt)) {
        return ProactiveMemoryPreparationOutcome.notReady();
      }
      Instant expiresAt = expiresAt(issuedAt);
      ProactiveMemoryOperationReference reference = operationReferences.next();
      String approvalId = ids.newApprovalId();
      String idempotencyKey = ids.newIdempotencyKey();
      String turnId = ids.newTurnId();
      String callId = ids.newTurnId();
      String binding =
          "proactive-memory:"
              + candidate.lease().job().jobRef().value()
              + ":"
              + candidate.lease().job().targetHash();
      var arguments =
          ProactiveMemoryCapsule.argumentsFor(
              candidate.source(),
              candidate.lease().job().jobRef().value(),
              candidate.lease().job().targetHash());
      String argumentsHash = ApprovalFingerprint.argumentsHash(arguments);
      String sessionBinding = ApprovalFingerprint.sessionBinding(binding);
      String fingerprint =
          ApprovalFingerprint.calculate(
              sessionBinding,
              turnId,
              callId,
              CAPABILITY_NAME,
              CAPABILITY_VERSION,
              ToolRisk.WRITE,
              argumentsHash,
              idempotencyKey,
              issuedAt,
              expiresAt);
      ApprovalRequest approval =
          new ApprovalRequest(
              approvalId,
              sessionBinding,
              turnId,
              callId,
              CAPABILITY_NAME,
              CAPABILITY_VERSION,
              ToolRisk.WRITE,
              argumentsHash,
              idempotencyKey,
              APPROVAL_SUMMARY,
              issuedAt,
              expiresAt,
              ApprovalRequest.FINGERPRINT_VERSION,
              fingerprint);
      ProactiveMemoryAnchor anchor =
          ProactiveMemoryAnchor.pending(
              reference, candidate.lease().job().jobRef(), candidate.lease().job().targetHash());
      ProactiveMemoryOperation operation =
          ProactiveMemoryOperation.pending(reference, approval, anchor, issuedAt);
      ProactiveMemoryCapsule capsule =
          ProactiveMemoryCapsule.forOperation(operation, candidate.source());
      EncryptedProactiveMemoryCapsule encrypted = cipher.encrypt(operation, capsule);
      ApprovalInboxReference approvalReference = approvalReferences.next();
      store.create(operation, ApprovalInboxEntry.pending(approvalReference, approval), encrypted);
      return ProactiveMemoryPreparationOutcome.pending(reference, approvalReference);
    } catch (RuntimeException failure) {
      candidate.releaseAfterMemoryCapturePreparationFailure();
      throw failure;
    }
  }

  private Instant expiresAt(Instant issuedAt) {
    try {
      return issuedAt.plus(approvalTimeout);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("审批到期时间超出范围", exception);
    }
  }
}

final class ProactiveMemoryPreparationOutcome {
  enum Kind {
    PENDING,
    NOT_READY,
    CANCELLED,
    ALREADY_PREPARED
  }

  private final Kind kind;
  private final ProactiveMemoryOperationReference reference;
  private final ApprovalInboxReference approvalReference;

  private ProactiveMemoryPreparationOutcome(
      Kind kind,
      ProactiveMemoryOperationReference reference,
      ApprovalInboxReference approvalReference) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.reference = reference;
    this.approvalReference = approvalReference;
    if ((kind == Kind.PENDING) != (reference != null && approvalReference != null)) {
      throw new IllegalArgumentException("主动记忆准备结果无效");
    }
  }

  static ProactiveMemoryPreparationOutcome pending(
      ProactiveMemoryOperationReference reference, ApprovalInboxReference approvalReference) {
    return new ProactiveMemoryPreparationOutcome(
        Kind.PENDING,
        Objects.requireNonNull(reference, "reference"),
        Objects.requireNonNull(approvalReference, "approvalReference"));
  }

  static ProactiveMemoryPreparationOutcome notReady() {
    return new ProactiveMemoryPreparationOutcome(Kind.NOT_READY, null, null);
  }

  static ProactiveMemoryPreparationOutcome cancelled() {
    return new ProactiveMemoryPreparationOutcome(Kind.CANCELLED, null, null);
  }

  static ProactiveMemoryPreparationOutcome alreadyPrepared() {
    return new ProactiveMemoryPreparationOutcome(Kind.ALREADY_PREPARED, null, null);
  }

  Kind kind() {
    return kind;
  }

  @Override
  public String toString() {
    return "ProactiveMemoryPreparationOutcome[kind="
        + kind
        + ", reference="
        + (reference == null ? "absent" : "<redacted>")
        + ", approvalReference="
        + (approvalReference == null ? "absent" : "<redacted>")
        + "]";
  }
}
