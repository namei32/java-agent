package io.namei.agent.kernel.error;

public final class ModelTimeoutException extends RuntimeException {
  public ModelTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
