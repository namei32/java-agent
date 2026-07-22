package io.namei.agent.kernel.error;

/** 请求超过上下文上限而被 Provider 拒绝。 */
public final class ModelContextLimitException extends RuntimeException {
  public ModelContextLimitException(Throwable cause) {
    super("模型上下文超出限制", cause);
  }
}
