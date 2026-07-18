package io.namei.agent.application;

/** Stable fail-closed signal for an unavailable or unauthenticated operation capsule. */
public final class PendingOperationCapsuleException extends RuntimeException {
  public PendingOperationCapsuleException() {
    super("待审批操作参数胶囊不可用");
  }

  public PendingOperationCapsuleException(Throwable cause) {
    super("待审批操作参数胶囊不可用", cause);
  }
}
