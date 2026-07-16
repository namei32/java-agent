package io.namei.agent.adapter.sqlite;

import java.util.Objects;

public final class ChannelLedgerRepositoryException extends RuntimeException {
  private final ChannelLedgerRepositoryFailure failure;

  private ChannelLedgerRepositoryException(
      ChannelLedgerRepositoryFailure failure, String message, Throwable cause) {
    super(message, cause);
    this.failure = Objects.requireNonNull(failure, "failure");
  }

  public ChannelLedgerRepositoryFailure failure() {
    return failure;
  }

  static ChannelLedgerRepositoryException databaseUnavailable(Throwable cause) {
    return new ChannelLedgerRepositoryException(
        ChannelLedgerRepositoryFailure.DATABASE_UNAVAILABLE, "渠道账本数据库不可用", cause);
  }

  static ChannelLedgerRepositoryException schemaIncompatible() {
    return new ChannelLedgerRepositoryException(
        ChannelLedgerRepositoryFailure.SCHEMA_INCOMPATIBLE, "渠道账本 Schema 不兼容", null);
  }

  static ChannelLedgerRepositoryException backupFailed(Throwable cause) {
    return new ChannelLedgerRepositoryException(
        ChannelLedgerRepositoryFailure.BACKUP_FAILED, "渠道账本备份失败", cause);
  }

  static ChannelLedgerRepositoryException idempotencyConflict() {
    return new ChannelLedgerRepositoryException(
        ChannelLedgerRepositoryFailure.IDEMPOTENCY_CONFLICT, "渠道账本幂等冲突", null);
  }

  static ChannelLedgerRepositoryException capacityExceeded() {
    return new ChannelLedgerRepositoryException(
        ChannelLedgerRepositoryFailure.CAPACITY_EXCEEDED, "渠道账本容量已耗尽", null);
  }

  static ChannelLedgerRepositoryException staleWrite() {
    return new ChannelLedgerRepositoryException(
        ChannelLedgerRepositoryFailure.STALE_WRITE, "渠道账本写入已过期", null);
  }

  static ChannelLedgerRepositoryException operationFailed(Throwable cause) {
    return new ChannelLedgerRepositoryException(
        ChannelLedgerRepositoryFailure.OPERATION_FAILED, "渠道账本操作失败", cause);
  }
}
