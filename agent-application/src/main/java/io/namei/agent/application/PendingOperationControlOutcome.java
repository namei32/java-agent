package io.namei.agent.application;

/** Safe, stable result of one local pending-operation control action. */
public enum PendingOperationControlOutcome {
  RESUMED,
  NOT_FOUND,
  NOT_RESUMABLE,
  UNKNOWN_REQUIRES_OPERATOR,
  CANCELLED,
  ALREADY_TERMINAL,
  NOT_CANCELLABLE
}
