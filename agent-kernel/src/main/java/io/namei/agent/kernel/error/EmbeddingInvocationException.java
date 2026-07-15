package io.namei.agent.kernel.error;

public final class EmbeddingInvocationException extends RuntimeException {
  public EmbeddingInvocationException(Throwable cause) {
    super("Embedding 调用失败", cause);
  }
}
