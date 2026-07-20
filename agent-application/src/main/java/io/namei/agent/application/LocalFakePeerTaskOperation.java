package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.proactive.LocalFakePeerCard;
import io.namei.agent.kernel.proactive.PeerTaskRef;
import io.namei.agent.kernel.proactive.PeerTaskState;
import java.time.Instant;
import java.util.Objects;

/** Dedicated P4 pending state; it has neither a process handle nor remote peer information. */
final class LocalFakePeerTaskOperation {
  enum State {
    PENDING_APPROVAL,
    APPROVED_PENDING_RESUME,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
    COMMIT_UNREPORTED,
    EXPIRED;

    static State from(PeerTaskState terminal) {
      return switch (Objects.requireNonNull(terminal, "terminal")) {
        case SUCCEEDED -> SUCCEEDED;
        case FAILED -> FAILED;
        case CANCELLED -> CANCELLED;
        case PENDING, RUNNING -> throw new IllegalArgumentException("Fake Peer 结果必须为终态");
      };
    }
  }

  private final PeerTaskRef reference;
  private final ApprovalRequest approval;
  private final LocalFakePeerAnchor anchor;
  private final State state;
  private final Instant changedAt;

  private LocalFakePeerTaskOperation(
      PeerTaskRef reference,
      ApprovalRequest approval,
      LocalFakePeerAnchor anchor,
      State state,
      Instant changedAt) {
    this.reference = Objects.requireNonNull(reference, "reference");
    this.approval = Objects.requireNonNull(approval, "approval");
    this.anchor = Objects.requireNonNull(anchor, "anchor");
    this.state = Objects.requireNonNull(state, "state");
    this.changedAt = Objects.requireNonNull(changedAt, "changedAt");
    if (!anchor.reference().equals(reference) || changedAt.isBefore(approval.issuedAt())) {
      throw new IllegalArgumentException("本地 Fake Peer 操作绑定或时间无效");
    }
    if (state == State.PENDING_APPROVAL && !changedAt.equals(approval.issuedAt())) {
      throw new IllegalArgumentException("待审批本地 Fake Peer 操作必须在审批签发时创建");
    }
  }

  static LocalFakePeerTaskOperation pending(
      PeerTaskRef reference,
      ApprovalRequest approval,
      LocalFakePeerAnchor anchor,
      Instant createdAt) {
    return new LocalFakePeerTaskOperation(
        reference, approval, anchor, State.PENDING_APPROVAL, createdAt);
  }

  LocalFakePeerTaskOperation transition(State next, Instant at) {
    Objects.requireNonNull(next, "next");
    Objects.requireNonNull(at, "at");
    if (at.isBefore(changedAt) || !permits(next, at)) {
      throw new IllegalStateException("不允许的本地 Fake Peer 状态转换: " + state + " -> " + next);
    }
    return new LocalFakePeerTaskOperation(reference, approval, anchor, next, at);
  }

  LocalFakePeerTaskOperation withAnchor(LocalFakePeerAnchor nextAnchor) {
    return new LocalFakePeerTaskOperation(
        reference, approval, Objects.requireNonNull(nextAnchor, "nextAnchor"), state, changedAt);
  }

  PeerTaskRef reference() {
    return reference;
  }

  ApprovalRequest approval() {
    return approval;
  }

  LocalFakePeerAnchor anchor() {
    return anchor;
  }

  State state() {
    return state;
  }

  boolean isTerminal() {
    return switch (state) {
      case PENDING_APPROVAL, APPROVED_PENDING_RESUME, RUNNING -> false;
      case SUCCEEDED, FAILED, CANCELLED, UNKNOWN, COMMIT_UNREPORTED, EXPIRED -> true;
    };
  }

  private boolean permits(State next, Instant at) {
    return switch (state) {
      case PENDING_APPROVAL ->
          switch (next) {
            case APPROVED_PENDING_RESUME -> at.isBefore(approval.expiresAt());
            case EXPIRED -> !at.isBefore(approval.expiresAt());
            case CANCELLED -> true;
            default -> false;
          };
      case APPROVED_PENDING_RESUME ->
          switch (next) {
            case RUNNING -> at.isBefore(approval.expiresAt());
            case EXPIRED -> !at.isBefore(approval.expiresAt());
            case CANCELLED -> true;
            default -> false;
          };
      case RUNNING ->
          next == State.SUCCEEDED
              || next == State.FAILED
              || next == State.CANCELLED
              || next == State.UNKNOWN;
      case SUCCEEDED, FAILED -> next == State.COMMIT_UNREPORTED;
      case CANCELLED -> next == State.UNKNOWN;
      case UNKNOWN, COMMIT_UNREPORTED, EXPIRED -> false;
    };
  }

  @Override
  public String toString() {
    return "LocalFakePeerTaskOperation[reference=<redacted>, approval=<redacted>, anchor=<redacted>, state="
        + state
        + "]";
  }
}

final class LocalFakePeerAnchor {
  enum State {
    PENDING_APPROVAL,
    CANCELLED,
    COMMITTED
  }

  private static final String AUDIT_TARGET_HASH = "c".repeat(64);

  private final PeerTaskRef reference;
  private final LocalFakePeerCard card;
  private final long version;
  private final State state;

  private LocalFakePeerAnchor(
      PeerTaskRef reference, LocalFakePeerCard card, long version, State state) {
    this.reference = Objects.requireNonNull(reference, "reference");
    this.card = Objects.requireNonNull(card, "card");
    this.version = version;
    this.state = Objects.requireNonNull(state, "state");
    if (!card.equals(LocalFakePeerCard.approved()) || version != 0) {
      throw new IllegalArgumentException("本地 Fake Peer Anchor 无效");
    }
  }

  static LocalFakePeerAnchor pending(PeerTaskRef reference, LocalFakePeerCard card) {
    return new LocalFakePeerAnchor(reference, card, 0, State.PENDING_APPROVAL);
  }

  LocalFakePeerAnchor transition(State next) {
    if (state != State.PENDING_APPROVAL || next == State.PENDING_APPROVAL) {
      throw new IllegalStateException("不允许的本地 Fake Peer Anchor 状态转换");
    }
    return new LocalFakePeerAnchor(reference, card, version, next);
  }

  PeerTaskRef reference() {
    return reference;
  }

  LocalFakePeerCard card() {
    return card;
  }

  long version() {
    return version;
  }

  State state() {
    return state;
  }

  String auditTargetHash() {
    return AUDIT_TARGET_HASH;
  }

  @Override
  public String toString() {
    return "LocalFakePeerAnchor[reference=<redacted>, card=<redacted>, version="
        + version
        + ", state="
        + state
        + "]";
  }
}
