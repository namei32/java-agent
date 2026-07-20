package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Dedicated P3 pending state. It deliberately has no Chat Session or public Memory identifiers. */
final class ProactiveMemoryOperation {
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

  private final ProactiveMemoryOperationReference reference;
  private final ApprovalRequest approval;
  private final ProactiveMemoryAnchor anchor;
  private final State state;
  private final Instant changedAt;

  private ProactiveMemoryOperation(
      ProactiveMemoryOperationReference reference,
      ApprovalRequest approval,
      ProactiveMemoryAnchor anchor,
      State state,
      Instant changedAt) {
    this.reference = Objects.requireNonNull(reference, "reference");
    this.approval = Objects.requireNonNull(approval, "approval");
    this.anchor = Objects.requireNonNull(anchor, "anchor");
    this.state = Objects.requireNonNull(state, "state");
    this.changedAt = Objects.requireNonNull(changedAt, "changedAt");
    if (!anchor.reference().equals(reference) || changedAt.isBefore(approval.issuedAt())) {
      throw new IllegalArgumentException("主动记忆操作绑定或时间无效");
    }
    if (state == State.PENDING_APPROVAL && !changedAt.equals(approval.issuedAt())) {
      throw new IllegalArgumentException("待审批主动记忆操作必须在审批签发时创建");
    }
  }

  static ProactiveMemoryOperation pending(
      ProactiveMemoryOperationReference reference,
      ApprovalRequest approval,
      ProactiveMemoryAnchor anchor,
      Instant createdAt) {
    return new ProactiveMemoryOperation(
        reference, approval, anchor, State.PENDING_APPROVAL, createdAt);
  }

  ProactiveMemoryOperation transition(State next, Instant at) {
    Objects.requireNonNull(next, "next");
    Objects.requireNonNull(at, "at");
    if (at.isBefore(changedAt) || !permits(next, at)) {
      throw new IllegalStateException("不允许的主动记忆状态转换: " + state + " -> " + next);
    }
    return new ProactiveMemoryOperation(reference, approval, anchor, next, at);
  }

  ProactiveMemoryOperation withAnchor(ProactiveMemoryAnchor nextAnchor) {
    return new ProactiveMemoryOperation(
        reference, approval, Objects.requireNonNull(nextAnchor, "nextAnchor"), state, changedAt);
  }

  ProactiveMemoryOperationReference reference() {
    return reference;
  }

  ApprovalRequest approval() {
    return approval;
  }

  ProactiveMemoryAnchor anchor() {
    return anchor;
  }

  State state() {
    return state;
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
    return "ProactiveMemoryOperation[reference=<redacted>, approval=<redacted>, anchor=<redacted>, state="
        + state
        + "]";
  }
}

final class ProactiveMemoryAnchor {
  enum State {
    PENDING_APPROVAL,
    CANCELLED,
    COMMITTED
  }

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  private final ProactiveMemoryOperationReference reference;
  private final ProactiveJobRef jobRef;
  private final String targetHash;
  private final long version;
  private final State state;

  private ProactiveMemoryAnchor(
      ProactiveMemoryOperationReference reference,
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
      throw new IllegalArgumentException("主动记忆 Anchor 无效");
    }
  }

  static ProactiveMemoryAnchor pending(
      ProactiveMemoryOperationReference reference, ProactiveJobRef jobRef, String targetHash) {
    return new ProactiveMemoryAnchor(reference, jobRef, targetHash, 0, State.PENDING_APPROVAL);
  }

  ProactiveMemoryAnchor transition(State next) {
    if (state != State.PENDING_APPROVAL || next == State.PENDING_APPROVAL) {
      throw new IllegalStateException("不允许的主动记忆 Anchor 状态转换");
    }
    return new ProactiveMemoryAnchor(reference, jobRef, targetHash, version, next);
  }

  ProactiveMemoryOperationReference reference() {
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
    return "ProactiveMemoryAnchor[reference=<redacted>, jobRef=<redacted>, targetHash=<redacted>, version="
        + version
        + ", state="
        + state
        + "]";
  }
}
