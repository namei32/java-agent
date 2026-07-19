package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Creates P2-B Pending state only; it has no delivery, Scheduler, Tool, or Bootstrap authority. */
final class ProactiveDeliveryPendingProducer {
  static final String CAPABILITY_NAME = "proactive_prepare_delivery";
  static final String CAPABILITY_VERSION = "r14-proactive-fake-delivery-v1";
  private static final String APPROVAL_SUMMARY = "请求准备本地主动投递。";

  private final ProactiveDeliveryPendingStore store;
  private final ProactiveDeliveryOperationReferenceGenerator operationReferences;
  private final ApprovalInboxReferenceGenerator approvalReferences;
  private final IdGenerator ids;
  private final ProactiveDeliveryCapsuleCipher cipher;
  private final FakeProactiveRecipientReference recipient;
  private final Clock clock;
  private final Duration approvalTimeout;

  ProactiveDeliveryPendingProducer(
      ProactiveDeliveryPendingStore store,
      ProactiveDeliveryOperationReferenceGenerator operationReferences,
      ApprovalInboxReferenceGenerator approvalReferences,
      IdGenerator ids,
      ProactiveDeliveryCapsuleCipher cipher,
      FakeProactiveRecipientReference recipient,
      Clock clock,
      Duration approvalTimeout) {
    this.store = Objects.requireNonNull(store, "store");
    this.operationReferences = Objects.requireNonNull(operationReferences, "operationReferences");
    this.approvalReferences = Objects.requireNonNull(approvalReferences, "approvalReferences");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.recipient = Objects.requireNonNull(recipient, "recipient");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout");
    if (approvalTimeout.isNegative() || approvalTimeout.isZero()) {
      throw new IllegalArgumentException("审批有效期必须大于零");
    }
  }

  public ProactiveDeliveryPreparationOutcome prepare(
      LocalProactiveCandidateResult candidateResult, TurnCancellation cancellation) {
    Objects.requireNonNull(candidateResult, "candidateResult");
    Objects.requireNonNull(cancellation, "cancellation");
    if (cancellation.isCancellationRequested()) {
      return ProactiveDeliveryPreparationOutcome.cancelled();
    }
    LocalProactiveCandidate candidate = candidateResult.candidateForPreparation().orElse(null);
    if (candidate == null) {
      return ProactiveDeliveryPreparationOutcome.notReady();
    }
    if (!candidate.tryClaimForPreparation()) {
      return ProactiveDeliveryPreparationOutcome.alreadyPrepared();
    }
    try {
      if (cancellation.isCancellationRequested()) {
        candidate.releaseAfterPreparationFailure();
        return ProactiveDeliveryPreparationOutcome.cancelled();
      }
      Instant issuedAt = clock.instant();
      if (!candidate.lease().expiresAt().isAfter(issuedAt)) {
        return ProactiveDeliveryPreparationOutcome.notReady();
      }
      Instant expiresAt = expiresAt(issuedAt);
      ProactiveDeliveryOperationReference reference = operationReferences.next();
      String approvalId = ids.newApprovalId();
      String idempotencyKey = ids.newIdempotencyKey();
      String turnId = ids.newTurnId();
      String callId = ids.newTurnId();
      String binding =
          "proactive:"
              + candidate.lease().job().jobRef().value()
              + ":"
              + candidate.lease().job().targetHash();
      var arguments =
          ProactiveDeliveryCapsule.argumentsFor(
              recipient,
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
              ToolRisk.EXTERNAL_SIDE_EFFECT,
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
              ToolRisk.EXTERNAL_SIDE_EFFECT,
              argumentsHash,
              idempotencyKey,
              APPROVAL_SUMMARY,
              issuedAt,
              expiresAt,
              ApprovalRequest.FINGERPRINT_VERSION,
              fingerprint);
      ProactiveDeliveryAnchor anchor =
          ProactiveDeliveryAnchor.pending(
              reference, candidate.lease().job().jobRef(), candidate.lease().job().targetHash());
      ProactiveDeliveryOperation operation =
          ProactiveDeliveryOperation.pending(reference, approval, anchor, issuedAt);
      ProactiveDeliveryCapsule capsule =
          ProactiveDeliveryCapsule.forOperation(operation, recipient, candidate.source());
      EncryptedProactiveDeliveryCapsule encrypted = cipher.encrypt(operation, capsule);
      ApprovalInboxReference approvalReference = approvalReferences.next();
      store.create(operation, ApprovalInboxEntry.pending(approvalReference, approval), encrypted);
      return ProactiveDeliveryPreparationOutcome.pending(reference, approvalReference);
    } catch (RuntimeException failure) {
      candidate.releaseAfterPreparationFailure();
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

final class ProactiveDeliveryPreparationOutcome {
  public enum Kind {
    PENDING,
    NOT_READY,
    CANCELLED,
    ALREADY_PREPARED
  }

  private final Kind kind;
  private final ProactiveDeliveryOperationReference reference;
  private final ApprovalInboxReference approvalReference;

  private ProactiveDeliveryPreparationOutcome(
      Kind kind,
      ProactiveDeliveryOperationReference reference,
      ApprovalInboxReference approvalReference) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.reference = reference;
    this.approvalReference = approvalReference;
    if ((kind == Kind.PENDING) != (reference != null && approvalReference != null)) {
      throw new IllegalArgumentException("主动投递准备结果无效");
    }
  }

  static ProactiveDeliveryPreparationOutcome pending(
      ProactiveDeliveryOperationReference reference, ApprovalInboxReference approvalReference) {
    return new ProactiveDeliveryPreparationOutcome(
        Kind.PENDING,
        Objects.requireNonNull(reference, "reference"),
        Objects.requireNonNull(approvalReference, "approvalReference"));
  }

  static ProactiveDeliveryPreparationOutcome notReady() {
    return new ProactiveDeliveryPreparationOutcome(Kind.NOT_READY, null, null);
  }

  static ProactiveDeliveryPreparationOutcome cancelled() {
    return new ProactiveDeliveryPreparationOutcome(Kind.CANCELLED, null, null);
  }

  static ProactiveDeliveryPreparationOutcome alreadyPrepared() {
    return new ProactiveDeliveryPreparationOutcome(Kind.ALREADY_PREPARED, null, null);
  }

  public Kind kind() {
    return kind;
  }

  @Override
  public String toString() {
    return "ProactiveDeliveryPreparationOutcome[kind="
        + kind
        + ", reference="
        + (reference == null ? "absent" : "<redacted>")
        + ", approvalReference="
        + (approvalReference == null ? "absent" : "<redacted>")
        + "]";
  }
}
