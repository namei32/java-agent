package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.error.SessionPersistenceException;

public final class SqliteRepositoryException extends SessionPersistenceException {
  public SqliteRepositoryException(String message, Throwable cause) {
    super(message, cause);
  }

  public SqliteRepositoryException(String message) {
    super(message);
  }
}
