package io.namei.agent.adapter.sqlite;

public enum ProactiveRepositoryFailure {
  DATABASE_UNAVAILABLE,
  SCHEMA_INCOMPATIBLE,
  DUPLICATE_IDEMPOTENCY_KEY,
  OPERATION_FAILED
}
