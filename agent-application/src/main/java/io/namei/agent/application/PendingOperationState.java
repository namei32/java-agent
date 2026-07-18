package io.namei.agent.application;

public enum PendingOperationState {
  PENDING_APPROVAL,
  APPROVED_PENDING_RESUME,
  CONSUMING,
  SUCCEEDED,
  FAILED,
  UNKNOWN,
  COMMIT_UNREPORTED,
  DENIED,
  EXPIRED,
  CANCELLED,
  STALE_SESSION;

  public boolean isTerminal() {
    return switch (this) {
      case PENDING_APPROVAL, APPROVED_PENDING_RESUME, CONSUMING, SUCCEEDED -> false;
      case FAILED, UNKNOWN, COMMIT_UNREPORTED, DENIED, EXPIRED, CANCELLED, STALE_SESSION -> true;
    };
  }
}
