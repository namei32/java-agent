package io.namei.agent.kernel.model;

/** Internal lifecycle of the Session-side anchor for a previously pending Tool operation. */
public enum PendingTurnAnchorState {
  PENDING_APPROVAL,
  CANCELLED,
  STALE_SESSION,
  COMMITTED;

  public boolean isTerminal() {
    return this != PENDING_APPROVAL;
  }
}
