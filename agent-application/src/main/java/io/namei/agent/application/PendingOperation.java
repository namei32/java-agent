package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import java.time.Instant;
import java.util.Objects;

/**
 * 等待或正在消费 Approval 的精确 Tool Operation 所对应的不可变、无执行模型。
 *
 * <p>参数胶囊持久化、Approval 消费、持久 Ledger 预留和 Invoker 调用均有意置于该类型之外。
 */
public record PendingOperation(
    PendingOperationReference reference,
    ApprovalRequest approval,
    long expectedNextSequence,
    PendingOperationState state,
    Instant stateChangedAt) {
  public PendingOperation {
    reference = Objects.requireNonNull(reference, "reference");
    approval = Objects.requireNonNull(approval, "approval");
    if (expectedNextSequence < 0) {
      throw new IllegalArgumentException("预期 Session 序号不能为负数");
    }
    state = Objects.requireNonNull(state, "state");
    stateChangedAt = Objects.requireNonNull(stateChangedAt, "stateChangedAt");
    if (stateChangedAt.isBefore(approval.issuedAt())) {
      throw new IllegalArgumentException("操作状态时间不能早于审批签发时间");
    }
    if (state == PendingOperationState.PENDING_APPROVAL
        && !stateChangedAt.equals(approval.issuedAt())) {
      throw new IllegalArgumentException("待审批操作必须在审批签发时间创建");
    }
  }

  public static PendingOperation pending(
      PendingOperationReference reference,
      ApprovalRequest approval,
      long expectedNextSequence,
      Instant createdAt) {
    return new PendingOperation(
        reference,
        approval,
        expectedNextSequence,
        PendingOperationState.PENDING_APPROVAL,
        createdAt);
  }

  public PendingOperation transitionTo(PendingOperationState next, Instant changedAt) {
    Objects.requireNonNull(next, "next");
    Objects.requireNonNull(changedAt, "changedAt");
    if (changedAt.isBefore(stateChangedAt)) {
      throw new IllegalArgumentException("操作状态时间不能倒退");
    }
    if (!permits(state, next, changedAt)) {
      throw new IllegalStateException("不允许的待审批操作状态转换: " + state + " -> " + next);
    }
    return new PendingOperation(reference, approval, expectedNextSequence, next, changedAt);
  }

  public boolean isTerminal() {
    return state.isTerminal();
  }

  private boolean permits(PendingOperationState current, PendingOperationState next, Instant at) {
    return switch (current) {
      case PENDING_APPROVAL ->
          switch (next) {
            case APPROVED_PENDING_RESUME, DENIED -> at.isBefore(approval.expiresAt());
            case EXPIRED -> !at.isBefore(approval.expiresAt());
            case CANCELLED, STALE_SESSION -> true;
            default -> false;
          };
      case APPROVED_PENDING_RESUME ->
          switch (next) {
            case CONSUMING -> at.isBefore(approval.expiresAt());
            case EXPIRED -> !at.isBefore(approval.expiresAt());
            case CANCELLED, STALE_SESSION -> true;
            default -> false;
          };
      case CONSUMING ->
          next == PendingOperationState.SUCCEEDED
              || next == PendingOperationState.FAILED
              || next == PendingOperationState.UNKNOWN;
      case SUCCEEDED -> next == PendingOperationState.COMMIT_UNREPORTED;
      case FAILED, UNKNOWN, COMMIT_UNREPORTED, DENIED, EXPIRED, CANCELLED, STALE_SESSION -> false;
    };
  }

  @Override
  public String toString() {
    return "PendingOperation[reference=<redacted>, approval=<redacted>, expectedNextSequence="
        + expectedNextSequence
        + ", state="
        + state
        + ", stateChangedAt="
        + stateChangedAt
        + "]";
  }
}
