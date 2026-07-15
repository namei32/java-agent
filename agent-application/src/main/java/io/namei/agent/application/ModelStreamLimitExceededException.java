package io.namei.agent.application;

public final class ModelStreamLimitExceededException extends RuntimeException {
  public ModelStreamLimitExceededException(String message) {
    super(message);
  }
}
