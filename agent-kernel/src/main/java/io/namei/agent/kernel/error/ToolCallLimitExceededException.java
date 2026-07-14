package io.namei.agent.kernel.error;

public final class ToolCallLimitExceededException extends RuntimeException {
  public ToolCallLimitExceededException(String message) {
    super(message);
  }
}
