package io.namei.agent.adapter.sqlite;

import java.util.Objects;

public final class JavaMemoryRepositoryException extends RuntimeException {
  private final JavaMemoryRepositoryFailure failure;

  private JavaMemoryRepositoryException(
      JavaMemoryRepositoryFailure failure, String message, Throwable cause) {
    super(message, cause);
    this.failure = Objects.requireNonNull(failure, "failure");
  }

  public JavaMemoryRepositoryFailure failure() {
    return failure;
  }

  static JavaMemoryRepositoryException databaseUnavailable(Throwable cause) {
    return new JavaMemoryRepositoryException(
        JavaMemoryRepositoryFailure.DATABASE_UNAVAILABLE, "Java Memory 数据库不可用", cause);
  }

  static JavaMemoryRepositoryException schemaIncompatible() {
    return new JavaMemoryRepositoryException(
        JavaMemoryRepositoryFailure.SCHEMA_INCOMPATIBLE, "Memory Schema 不兼容", null);
  }

  static JavaMemoryRepositoryException backupFailed(Throwable cause) {
    return new JavaMemoryRepositoryException(
        JavaMemoryRepositoryFailure.BACKUP_FAILED, "Memory Schema 备份失败", cause);
  }
}
