package io.namei.agent.application;

public final class MemoryContextUnavailableException extends RuntimeException {
  public MemoryContextUnavailableException() {
    super("记忆上下文当前不可用");
  }
}
