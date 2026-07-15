package io.namei.agent.kernel.error;

public final class InvalidEmbeddingResponseException extends RuntimeException {
  public InvalidEmbeddingResponseException() {
    super("Embedding 响应无效");
  }
}
