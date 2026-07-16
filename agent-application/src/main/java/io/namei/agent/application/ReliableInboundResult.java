package io.namei.agent.application;

import java.util.Objects;

public record ReliableInboundResult(Status status, String turnId, long nextSequence) {
  public enum Status {
    TURN_SCHEDULED,
    IN_PROGRESS,
    ALREADY_TERMINAL,
    EXECUTION_UNKNOWN,
    IGNORED_RECORDED,
    CONTROL_APPLIED,
    FEEDBACK_QUEUED
  }

  public ReliableInboundResult {
    Objects.requireNonNull(status, "status");
    turnId = turnId == null ? "" : turnId;
    if (nextSequence < 0) {
      throw new IllegalArgumentException("nextSequence 不能为负数");
    }
  }

  @Override
  public String toString() {
    return "ReliableInboundResult[status=" + status + ", sensitiveFields=<redacted>]";
  }
}
