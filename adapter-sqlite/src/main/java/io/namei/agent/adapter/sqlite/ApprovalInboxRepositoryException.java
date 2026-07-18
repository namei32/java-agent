package io.namei.agent.adapter.sqlite;

/** Stable internal signal: callers must fail closed rather than invent an inbox outcome. */
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
