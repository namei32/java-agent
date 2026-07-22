package io.namei.agent.application;

/** Operation 胶囊不可用或认证失败时使用的稳定关闭式失败信号。 */
public final class PendingOperationCapsuleException extends RuntimeException {
  public PendingOperationCapsuleException() {
    super("待审批操作参数胶囊不可用");
  }

  public PendingOperationCapsuleException(Throwable cause) {
    super("待审批操作参数胶囊不可用", cause);
  }
}
