package io.namei.agent.kernel.error;

/**
 * A provider rejected content under its safety policy. The provider payload remains in the cause.
 */
public final class ModelSafetyRejectedException extends RuntimeException {
  public ModelSafetyRejectedException(Throwable cause) {
    super("模型内容安全审查拒绝请求", cause);
  }
}
