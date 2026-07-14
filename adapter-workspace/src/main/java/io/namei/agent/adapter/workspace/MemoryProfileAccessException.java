package io.namei.agent.adapter.workspace;

public final class MemoryProfileAccessException extends RuntimeException {
  public MemoryProfileAccessException() {
    super("记忆 Profile 当前不可用");
  }
}
