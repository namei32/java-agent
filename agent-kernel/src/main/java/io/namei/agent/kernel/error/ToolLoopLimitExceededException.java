package io.namei.agent.kernel.error;

public final class ToolLoopLimitExceededException extends RuntimeException {
  public ToolLoopLimitExceededException(String message) {
    super(message);
  }
}
