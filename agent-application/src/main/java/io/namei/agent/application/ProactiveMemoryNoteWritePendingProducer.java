package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Creates P6 pending state only; it has no SQLite DML, worker, tool, or Bootstrap authority. */
final class ProactiveMemoryNoteWritePendingProducer {
  private static final String APPROVAL_SUMMARY = "请求保存本地主动记忆候选。";

  private final ProactiveMemoryNoteWritePendingStore store;
  private final ProactiveMemoryNoteWriteOperationReferenceGenerator operationReferences;
  private final ApprovalInboxReferenceGenerator approvalReferences;
  private final IdGenerator ids;
  private final ProactiveMemoryNoteWriteCapsuleCipher cipher;
  private final Clock clock;
  private final Duration approvalTimeout;

  ProactiveMemoryNoteWritePendingProducer(
      ProactiveMemoryNoteWritePendingStore store,
      ProactiveMemoryNoteWriteOperationReferenceGenerator operationReferences,
      ApprovalInboxReferenceGenerator approvalReferences,
      IdGenerator ids,
      ProactiveMemoryNoteWriteCapsuleCipher cipher,
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

  ProactiveMemoryNoteWritePreparationOutcome prepare(
      LocalProactiveCandidateResult candidateResult, TurnCancellation cancellation) {
    Objects.requireNonNull(candidateResult, "candidateResult");
    Objects.requireNonNull(cancellation, "cancellation");
    if (cancellation.isCancellationRequested()) {
      return ProactiveMemoryNoteWritePreparationOutcome.cancelled();
    }
    LocalProactiveCandidate candidate = candidateResult.candidateForPreparation().orElse(null);
    if (candidate == null) {
      return ProactiveMemoryNoteWritePreparationOutcome.notReady();
    }
    if (!candidate.tryClaimForMemoryNoteWrite()) {
      return ProactiveMemoryNoteWritePreparationOutcome.alreadyPrepared();
    }
    try {
      if (cancellation.isCancellationRequested()) {
        candidate.releaseAfterMemoryNoteWritePreparationFailure();
        return ProactiveMemoryNoteWritePreparationOutcome.cancelled();
      }
      Instant issuedAt = clock.instant();
      if (!candidate.lease().expiresAt().isAfter(issuedAt)) {
        candidate.releaseAfterMemoryNoteWritePreparationFailure();
        return ProactiveMemoryNoteWritePreparationOutcome.notReady();
      }
      Instant expiresAt = expiresAt(issuedAt);
      ProactiveMemoryNoteWriteOperationReference reference = operationReferences.next();
      String approvalId = ids.newApprovalId();
      String idempotencyKey = ids.newIdempotencyKey();
      String turnId = ids.newTurnId();
      String callId = ids.newTurnId();
      var anchor =
          ProactiveMemoryNoteWriteAnchor.pending(
              reference, candidate.lease().job().jobRef(), candidate.lease().job().targetHash());
      var provisionalApproval =
          new ApprovalRequest(
              approvalId,
              ApprovalFingerprint.sessionBinding(
                  "r14-p6-note:"
                      + candidate.lease().job().jobRef().value()
                      + ":"
                      + candidate.lease().job().targetHash()),
              turnId,
              callId,
              ProactiveMemoryNoteWriteCapability.CAPABILITY_NAME,
              ProactiveMemoryNoteWriteCapability.CAPABILITY_VERSION,
              ToolRisk.WRITE,
              ApprovalFingerprint.argumentsHash(
                  ProactiveMemoryNoteWriteCapsule.argumentsFor(
                      candidate.source(),
                      anchor.jobRef().value(),
                      anchor.targetHash(),
                      candidate.preparedAt())),
              idempotencyKey,
              APPROVAL_SUMMARY,
              issuedAt,
              expiresAt,
              ApprovalRequest.FINGERPRINT_VERSION,
              ApprovalFingerprint.calculate(
                  ApprovalFingerprint.sessionBinding(
                      "r14-p6-note:"
                          + candidate.lease().job().jobRef().value()
                          + ":"
                          + candidate.lease().job().targetHash()),
                  turnId,
                  callId,
                  ProactiveMemoryNoteWriteCapability.CAPABILITY_NAME,
                  ProactiveMemoryNoteWriteCapability.CAPABILITY_VERSION,
                  ToolRisk.WRITE,
                  ApprovalFingerprint.argumentsHash(
                      ProactiveMemoryNoteWriteCapsule.argumentsFor(
                          candidate.source(),
                          anchor.jobRef().value(),
                          anchor.targetHash(),
                          candidate.preparedAt())),
                  idempotencyKey,
                  issuedAt,
                  expiresAt));
      var operation =
          ProactiveMemoryNoteWriteOperation.pending(
              reference, provisionalApproval, anchor, issuedAt);
      var capsule =
          ProactiveMemoryNoteWriteCapsule.forOperation(
              operation, candidate.source(), candidate.preparedAt());
      EncryptedProactiveMemoryNoteWriteCapsule encrypted = cipher.encrypt(operation, capsule);
      ApprovalInboxReference approvalReference = approvalReferences.next();
      store.create(
          operation, ApprovalInboxEntry.pending(approvalReference, provisionalApproval), encrypted);
      return ProactiveMemoryNoteWritePreparationOutcome.pending(reference, approvalReference);
    } catch (RuntimeException failure) {
      candidate.releaseAfterMemoryNoteWritePreparationFailure();
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

final class ProactiveMemoryNoteWritePreparationOutcome {
  enum Kind {
    PENDING,
    NOT_READY,
    CANCELLED,
    ALREADY_PREPARED
  }

  private final Kind kind;
  private final ProactiveMemoryNoteWriteOperationReference reference;
  private final ApprovalInboxReference approvalReference;

  private ProactiveMemoryNoteWritePreparationOutcome(
      Kind kind,
      ProactiveMemoryNoteWriteOperationReference reference,
      ApprovalInboxReference approvalReference) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.reference = reference;
    this.approvalReference = approvalReference;
    if ((kind == Kind.PENDING) != (reference != null && approvalReference != null)) {
      throw new IllegalArgumentException("主动 NOTE 写入准备结果无效");
    }
  }

  static ProactiveMemoryNoteWritePreparationOutcome pending(
      ProactiveMemoryNoteWriteOperationReference reference,
      ApprovalInboxReference approvalReference) {
    return new ProactiveMemoryNoteWritePreparationOutcome(
        Kind.PENDING,
        Objects.requireNonNull(reference, "reference"),
        Objects.requireNonNull(approvalReference, "approvalReference"));
  }

  static ProactiveMemoryNoteWritePreparationOutcome notReady() {
    return new ProactiveMemoryNoteWritePreparationOutcome(Kind.NOT_READY, null, null);
  }

  static ProactiveMemoryNoteWritePreparationOutcome cancelled() {
    return new ProactiveMemoryNoteWritePreparationOutcome(Kind.CANCELLED, null, null);
  }

  static ProactiveMemoryNoteWritePreparationOutcome alreadyPrepared() {
    return new ProactiveMemoryNoteWritePreparationOutcome(Kind.ALREADY_PREPARED, null, null);
  }

  Kind kind() {
    return kind;
  }

  @Override
  public String toString() {
    return "ProactiveMemoryNoteWritePreparationOutcome[kind="
        + kind
        + ", reference="
        + (reference == null ? "absent" : "<redacted>")
        + ", approvalReference="
        + (approvalReference == null ? "absent" : "<redacted>")
        + "]";
  }
}
