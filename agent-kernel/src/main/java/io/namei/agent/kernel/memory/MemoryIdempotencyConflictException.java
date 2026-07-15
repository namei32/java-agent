package io.namei.agent.kernel.memory;

public final class MemoryIdempotencyConflictException extends RuntimeException {
  public MemoryIdempotencyConflictException() {
    super("Memory Request ID 已绑定其他参数");
  }
}
