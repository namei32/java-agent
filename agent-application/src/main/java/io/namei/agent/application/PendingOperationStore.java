package io.namei.agent.application;

import io.namei.agent.kernel.tool.ToolResult;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 用于非执行 Pending Operation 及其关联 Approval 记录的隔离持久 Store。
 *
 * <p>创建记录时必须原子持久化 Inbox 条目和加密胶囊。该 Port 不授权恢复、Tool 调用或 Ledger 状态转换。
 */
public interface PendingOperationStore {
  PendingOperation create(
      PendingOperation operation, ApprovalInboxEntry approval, PendingOperationCapsule capsule);

  Optional<PendingOperation> find(PendingOperationReference reference);

  /**
   * 仅当适配器重建完整 Operation、认证加密载荷并校验完整绑定后，才返回明文 Capsule。
   *
   * <p>这不构成执行权，也不得暴露密文、Nonce、密钥材料或原始持久化参数列。未来 Capability 在调用前仍必须取得自己的 Reservation。
   */
  default Optional<PendingOperationCapsule> loadVerifiedCapsule(
      PendingOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    throw new UnsupportedOperationException("Pending Operation Store 不支持已认证 Capsule 读取");
  }

  /** 在独立 Session Pending CAS 未提交后，将未消费 Operation 终态固化为过时；返回 false 表示没有可固化的 Pending Operation。 */
  default boolean markStaleSessionIfPending(
      PendingOperationReference reference, Instant observedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    throw new UnsupportedOperationException("Pending Operation Store 不支持 Session 过时固化");
  }

  /** 仅取消尚未进入持久化消费状态的 Operation。 */
  default PendingOperationCancelStatus cancelIfUnconsumed(
      PendingOperationReference reference, Instant observedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    throw new UnsupportedOperationException("Pending Operation Store 不支持取消");
  }

  /**
   * 原子消费已批准的 Inbox 记录，将其 Operation 推进到 {@code CONSUMING}，并创建唯一的持久副作用 Reservation。
   *
   * <p>返回值有意不包含 Tool 参数，也绝不调用 Tool。重放明确不构成执行权。
   */
  PendingOperationReservation reserveApproved(
      PendingOperationReference reference, Instant observedAt);

  PendingOperationLedgerEntry markRunning(PendingOperationReference reference, Instant observedAt);

  PendingOperationLedgerEntry markSucceeded(
      PendingOperationReference reference, ToolResult safeResult, Instant observedAt);

  PendingOperationLedgerEntry markFailedBeforeStart(
      PendingOperationReference reference, ToolResult safeResult, Instant observedAt);

  PendingOperationLedgerEntry markUnknown(
      PendingOperationReference reference, String errorCode, Instant observedAt);

  PendingOperation markCommitUnreported(PendingOperationReference reference, Instant observedAt);

  Optional<PendingOperationLedgerEntry> findLedger(PendingOperationReference reference);
}
