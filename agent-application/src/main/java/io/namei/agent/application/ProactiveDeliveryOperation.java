package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Dedicated Pending model for proactive Fake Delivery; it deliberately has no Chat Session field.
 */
final class ProactiveDeliveryOperation {
  enum State {
    PENDING_APPROVAL,
    APPROVED_PENDING_RESUME,
    CONSUMING,
    SUCCEEDED,
    UNKNOWN,
    COMMIT_UNREPORTED,
    DENIED,
    EXPIRED,
    CANCELLED
  }

  private final ProactiveDeliveryOperationReference reference;
  private final ApprovalRequest approval;
  private final ProactiveDeliveryAnchor anchor;
  private final State state;
  private final Instant changedAt;

  private ProactiveDeliveryOperation(
      ProactiveDeliveryOperationReference reference,
      ApprovalRequest approval,
      ProactiveDeliveryAnchor anchor,
      State state,
      Instant changedAt) {
    this.reference = Objects.requireNonNull(reference, "reference");
    this.approval = Objects.requireNonNull(approval, "approval");
    this.anchor = Objects.requireNonNull(anchor, "anchor");
    this.state = Objects.requireNonNull(state, "state");
    this.changedAt = Objects.requireNonNull(changedAt, "changedAt");
    if (!anchor.reference().equals(reference) || changedAt.isBefore(approval.issuedAt())) {
      throw new IllegalArgumentException("主动投递操作绑定或时间无效");
    }
    if (state == State.PENDING_APPROVAL && !changedAt.equals(approval.issuedAt())) {
      throw new IllegalArgumentException("待审批主动投递操作必须在审批签发时创建");
    }
  }

  static ProactiveDeliveryOperation pending(
      ProactiveDeliveryOperationReference reference,
      ApprovalRequest approval,
      ProactiveDeliveryAnchor anchor,
      Instant createdAt) {
    return new ProactiveDeliveryOperation(
        reference, approval, anchor, State.PENDING_APPROVAL, createdAt);
  }

  ProactiveDeliveryOperation transition(State next, Instant at) {
    Objects.requireNonNull(next, "next");
    Objects.requireNonNull(at, "at");
    if (at.isBefore(changedAt) || !permits(next, at)) {
      throw new IllegalStateException("不允许的主动投递状态转换: " + state + " -> " + next);
    }
    return new ProactiveDeliveryOperation(reference, approval, anchor, next, at);
  }

  ProactiveDeliveryOperation withAnchor(ProactiveDeliveryAnchor nextAnchor) {
    return new ProactiveDeliveryOperation(
        reference, approval, Objects.requireNonNull(nextAnchor, "nextAnchor"), state, changedAt);
  }

  ProactiveDeliveryOperationReference reference() {
    return reference;
  }

  ApprovalRequest approval() {
    return approval;
  }

  ProactiveDeliveryAnchor anchor() {
    return anchor;
  }

  State state() {
    return state;
  }

  Instant changedAt() {
    return changedAt;
  }

  boolean isTerminal() {
    return state == State.SUCCEEDED
        || state == State.UNKNOWN
        || state == State.COMMIT_UNREPORTED
        || state == State.DENIED
        || state == State.EXPIRED
        || state == State.CANCELLED;
  }

  private boolean permits(State next, Instant at) {
    return switch (state) {
      case PENDING_APPROVAL ->
          switch (next) {
            case APPROVED_PENDING_RESUME, DENIED -> at.isBefore(approval.expiresAt());
            case EXPIRED -> !at.isBefore(approval.expiresAt());
            case CANCELLED -> true;
            default -> false;
          };
      case APPROVED_PENDING_RESUME ->
          switch (next) {
            case CONSUMING -> at.isBefore(approval.expiresAt());
            case EXPIRED -> !at.isBefore(approval.expiresAt());
            case CANCELLED -> true;
            default -> false;
          };
      case CONSUMING -> next == State.SUCCEEDED || next == State.UNKNOWN;
      case SUCCEEDED -> next == State.COMMIT_UNREPORTED;
      case UNKNOWN, COMMIT_UNREPORTED, DENIED, EXPIRED, CANCELLED -> false;
    };
  }

  @Override
  public String toString() {
    return "ProactiveDeliveryOperation[reference=<redacted>, approval=<redacted>, anchor=<redacted>, state="
        + state
        + ", changedAt="
        + changedAt
        + "]";
  }
}

final class ProactiveDeliveryAnchor {
  enum State {
    PENDING_APPROVAL,
    CANCELLED,
    COMMITTED
  }

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  private final ProactiveDeliveryOperationReference reference;
  private final ProactiveJobRef jobRef;
  private final String targetHash;
  private final long version;
  private final State state;

  private ProactiveDeliveryAnchor(
      ProactiveDeliveryOperationReference reference,
      ProactiveJobRef jobRef,
      String targetHash,
      long version,
      State state) {
    this.reference = Objects.requireNonNull(reference, "reference");
    this.jobRef = Objects.requireNonNull(jobRef, "jobRef");
    this.targetHash = Objects.requireNonNull(targetHash, "targetHash");
    this.version = version;
    this.state = Objects.requireNonNull(state, "state");
    if (!HASH.matcher(targetHash).matches() || version != 0) {
      throw new IllegalArgumentException("主动投递 Anchor 无效");
    }
  }

  static ProactiveDeliveryAnchor pending(
      ProactiveDeliveryOperationReference reference, ProactiveJobRef jobRef, String targetHash) {
    return new ProactiveDeliveryAnchor(reference, jobRef, targetHash, 0, State.PENDING_APPROVAL);
  }

  ProactiveDeliveryAnchor transition(State next) {
    if (state != State.PENDING_APPROVAL || next == State.PENDING_APPROVAL) {
      throw new IllegalStateException("不允许的主动投递 Anchor 状态转换");
    }
    return new ProactiveDeliveryAnchor(reference, jobRef, targetHash, version, next);
  }

  ProactiveDeliveryOperationReference reference() {
    return reference;
  }

  ProactiveJobRef jobRef() {
    return jobRef;
  }

  String targetHash() {
    return targetHash;
  }

  long version() {
    return version;
  }

  State state() {
    return state;
  }

  @Override
  public String toString() {
    return "ProactiveDeliveryAnchor[reference=<redacted>, jobRef=<redacted>, targetHash=<redacted>, version="
        + version
        + ", state="
        + state
        + "]";
  }
}
