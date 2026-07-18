package io.namei.agent.adapter.sqlite;

import java.util.Objects;

public final class ProactiveRepositoryException extends RuntimeException {
  private final ProactiveRepositoryFailure failure;

  private ProactiveRepositoryException(
      ProactiveRepositoryFailure failure, String message, Throwable cause) {
    super(message, cause);
    this.failure = Objects.requireNonNull(failure, "failure");
  }

  public ProactiveRepositoryFailure failure() {
    return failure;
  }

  static ProactiveRepositoryException unavailable(Throwable cause) {
    return new ProactiveRepositoryException(
        ProactiveRepositoryFailure.DATABASE_UNAVAILABLE, "Proactive SQLite 数据库不可用", cause);
  }

  static ProactiveRepositoryException schemaIncompatible() {
    return new ProactiveRepositoryException(
        ProactiveRepositoryFailure.SCHEMA_INCOMPATIBLE, "Proactive SQLite Schema 不兼容", null);
  }

  static ProactiveRepositoryException duplicate(Throwable cause) {
    return new ProactiveRepositoryException(
        ProactiveRepositoryFailure.DUPLICATE_IDEMPOTENCY_KEY, "Proactive 幂等键重复", cause);
  }

  static ProactiveRepositoryException operationFailed(Throwable cause) {
    return new ProactiveRepositoryException(
        ProactiveRepositoryFailure.OPERATION_FAILED, "Proactive SQLite 操作失败", cause);
  }
}
