package io.namei.agent.adapter.sqlite;

/** 稳定的内部信号：调用方必须关闭式失败，不能臆造 Inbox 结果。 */
public final class ApprovalInboxRepositoryException extends RuntimeException {
  private ApprovalInboxRepositoryException(String message, Throwable cause) {
    super(message, cause);
  }

  static ApprovalInboxRepositoryException unavailable(Throwable cause) {
    return new ApprovalInboxRepositoryException("审批收件箱不可用", cause);
  }

  static ApprovalInboxRepositoryException incompatible() {
    return new ApprovalInboxRepositoryException("审批收件箱 Schema 不兼容", null);
  }

  static ApprovalInboxRepositoryException capacityExceeded() {
    return new ApprovalInboxRepositoryException("审批收件箱容量已满", null);
  }
}
