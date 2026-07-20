package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.proactive.LocalFakePeerCard;
import io.namei.agent.kernel.proactive.PeerTaskRef;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Creates P4 pending state only; it has no Tool, worker, launcher, process, or network authority.
 */
final class LocalFakePeerPendingProducer {
  static final String CAPABILITY_NAME = "local_fake_peer_task";
  static final String CAPABILITY_VERSION = "r14-local-fake-peer-v1";
  private static final String APPROVAL_SUMMARY = "请求运行本地 Fake Peer 固定任务。";

  private final LocalFakePeerPendingStore store;
  private final LocalFakePeerTaskReferenceGenerator references;
  private final ApprovalInboxReferenceGenerator approvalReferences;
  private final IdGenerator ids;
  private final LocalFakePeerCapsuleCipher cipher;
  private final Clock clock;
  private final Duration approvalTimeout;

  LocalFakePeerPendingProducer(
      LocalFakePeerPendingStore store,
      LocalFakePeerTaskReferenceGenerator references,
      ApprovalInboxReferenceGenerator approvalReferences,
      IdGenerator ids,
      LocalFakePeerCapsuleCipher cipher,
      Clock clock,
      Duration approvalTimeout) {
    this.store = Objects.requireNonNull(store, "store");
    this.references = Objects.requireNonNull(references, "references");
    this.approvalReferences = Objects.requireNonNull(approvalReferences, "approvalReferences");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout");
    if (approvalTimeout.isNegative() || approvalTimeout.isZero()) {
      throw new IllegalArgumentException("审批有效期必须大于零");
    }
  }

  LocalFakePeerPreparationOutcome prepare(LocalFakePeerCard card, TurnCancellation cancellation) {
    Objects.requireNonNull(card, "card");
    Objects.requireNonNull(cancellation, "cancellation");
    if (cancellation.isCancellationRequested()) {
      return LocalFakePeerPreparationOutcome.cancelled();
    }
    if (!card.equals(LocalFakePeerCard.approved())) {
      throw new LocalFakePeerPreparationException();
    }
    Instant issuedAt = clock.instant();
    Instant expiresAt = expiresAt(issuedAt);
    PeerTaskRef reference = references.next();
    String approvalId = ids.newApprovalId();
    String idempotencyKey = ids.newIdempotencyKey();
    String turnId = ids.newTurnId();
    String callId = ids.newTurnId();
    Map<String, Object> arguments = LocalFakePeerCapsule.argumentsFor(card);
    String argumentsHash = ApprovalFingerprint.argumentsHash(arguments);
    String sessionBinding =
        ApprovalFingerprint.sessionBinding(
            "local-fake-peer:" + card.manifest().identity().peerRef());
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
    LocalFakePeerTaskOperation operation =
        LocalFakePeerTaskOperation.pending(
            reference, approval, LocalFakePeerAnchor.pending(reference, card), issuedAt);
    EncryptedLocalFakePeerCapsule encrypted =
        cipher.encrypt(operation, LocalFakePeerCapsule.forOperation(operation));
    ApprovalInboxReference approvalReference = approvalReferences.next();
    store.create(operation, ApprovalInboxEntry.pending(approvalReference, approval), encrypted);
    return LocalFakePeerPreparationOutcome.pending(reference, approvalReference);
  }

  private Instant expiresAt(Instant issuedAt) {
    try {
      return issuedAt.plus(approvalTimeout);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("审批到期时间超出范围", exception);
    }
  }
}

@FunctionalInterface
interface LocalFakePeerTaskReferenceGenerator {
  PeerTaskRef next();
}

final class LocalFakePeerPreparationOutcome {
  enum Kind {
    PENDING,
    CANCELLED
  }

  private final Kind kind;
  private final PeerTaskRef reference;
  private final ApprovalInboxReference approvalReference;

  private LocalFakePeerPreparationOutcome(
      Kind kind, PeerTaskRef reference, ApprovalInboxReference approvalReference) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.reference = reference;
    this.approvalReference = approvalReference;
    if ((kind == Kind.PENDING) != (reference != null && approvalReference != null)) {
      throw new IllegalArgumentException("本地 Fake Peer 准备结果无效");
    }
  }

  static LocalFakePeerPreparationOutcome pending(
      PeerTaskRef reference, ApprovalInboxReference approvalReference) {
    return new LocalFakePeerPreparationOutcome(
        Kind.PENDING,
        Objects.requireNonNull(reference, "reference"),
        Objects.requireNonNull(approvalReference, "approvalReference"));
  }

  static LocalFakePeerPreparationOutcome cancelled() {
    return new LocalFakePeerPreparationOutcome(Kind.CANCELLED, null, null);
  }

  Kind kind() {
    return kind;
  }

  @Override
  public String toString() {
    return "LocalFakePeerPreparationOutcome[kind="
        + kind
        + ", reference="
        + (reference == null ? "absent" : "<redacted>")
        + ", approvalReference="
        + (approvalReference == null ? "absent" : "<redacted>")
        + "]";
  }
}

final class LocalFakePeerPreparationException extends RuntimeException {
  LocalFakePeerPreparationException() {
    super("LOCAL_FAKE_PEER_PREPARATION_FAILED");
  }
}
