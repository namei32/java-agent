package io.namei.agent.kernel.approval;

/** 审批请求从等待决定到消费或终止的持久状态。 */
public enum ApprovalState {
  /** 等待审批主体作出决定。 */
  PENDING,
  /** 已批准但尚未被执行路径原子消费。 */
  APPROVED,
  /** 批准已经被唯一执行路径消费。 */
  CONSUMED,
  /** 明确拒绝。 */
  DENIED,
  /** 未在有效期内完成审批或消费。 */
  EXPIRED,
  /** 因 Turn 或操作取消而终止。 */
  CANCELLED
}
