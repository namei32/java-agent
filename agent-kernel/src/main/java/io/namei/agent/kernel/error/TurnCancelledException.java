package io.namei.agent.kernel.error;

public final class TurnCancelledException extends RuntimeException {
  public TurnCancelledException(String message) {
    super(message);
  }
}
