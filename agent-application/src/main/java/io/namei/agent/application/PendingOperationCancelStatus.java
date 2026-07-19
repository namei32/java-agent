package io.namei.agent.application;

/** Stable cancellation result from the isolated pending-operation transaction boundary. */
public enum PendingOperationCancelStatus {
  CANCELLED,
  ALREADY_CANCELLED,
  ALREADY_TERMINAL,
  NOT_CANCELLABLE,
  NOT_FOUND
}
