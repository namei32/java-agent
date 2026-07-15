package io.namei.agent.kernel.memory;

public final class MemoryCandidateLimitExceededException extends RuntimeException {
  public MemoryCandidateLimitExceededException() {
    super("Memory 候选数量超过上限");
  }
}
