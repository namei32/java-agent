package io.namei.agent.kernel.error;

public class SessionPersistenceException extends RuntimeException {
  public SessionPersistenceException(String message) {
    super(message);
  }

  public SessionPersistenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
