package io.namei.agent.adapter.sqlite;

public enum ChannelLedgerRepositoryFailure {
  DATABASE_UNAVAILABLE,
  SCHEMA_INCOMPATIBLE,
  BACKUP_FAILED,
  IDEMPOTENCY_CONFLICT,
  CAPACITY_EXCEEDED,
  STALE_WRITE,
  OPERATION_FAILED
}
