package io.namei.agent.kernel.control;

public enum ControlCancelResult {
  CANCELLATION_REQUESTED,
  ALREADY_REQUESTED,
  ALREADY_CANCELLED,
  ALREADY_TERMINAL,
  NOT_FOUND
}
