package io.namei.agent.application;

/** Pending Operation Store 不可用或认证失败时使用的稳定关闭式失败信号。 */
public final class PendingOperationStoreException extends RuntimeException {
  public PendingOperationStoreException() {
    super("待审批操作存储不可用");
  }

  public PendingOperationStoreException(Throwable cause) {
    super("待审批操作存储不可用", cause);
  }
}
