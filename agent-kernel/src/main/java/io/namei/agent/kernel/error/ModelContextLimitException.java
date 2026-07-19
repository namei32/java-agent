package io.namei.agent.kernel.error;

/** A provider rejected the request because its context limit was exceeded. */
public final class ModelContextLimitException extends RuntimeException {
  public ModelContextLimitException(Throwable cause) {
    super("模型上下文超出限制", cause);
  }
}
