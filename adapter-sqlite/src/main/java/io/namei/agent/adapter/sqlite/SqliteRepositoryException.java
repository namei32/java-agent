package io.namei.agent.adapter.sqlite;

public final class SqliteRepositoryException extends RuntimeException {
  public SqliteRepositoryException(String message, Throwable cause) {
    super(message, cause);
  }

  public SqliteRepositoryException(String message) {
    super(message);
  }
}
