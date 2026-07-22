package io.namei.agent.kernel.model;

/** 先前 Pending Tool Operation 对应 Session 侧 Anchor 的内部生命周期。 */
public enum PendingTurnAnchorState {
  PENDING_APPROVAL,
  CANCELLED,
  STALE_SESSION,
  COMMITTED;

  public boolean isTerminal() {
    return this != PENDING_APPROVAL;
  }
}
