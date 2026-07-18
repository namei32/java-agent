package io.namei.agent.application;

/** Stable fail-closed signal for an unavailable or unauthenticated pending-operation store. */
public final class PendingOperationStoreException extends RuntimeException {
  public PendingOperationStoreException() {
    super("待审批操作存储不可用");
  }

  public PendingOperationStoreException(Throwable cause) {
    super("待审批操作存储不可用", cause);
  }
}
