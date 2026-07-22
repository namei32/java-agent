package io.namei.agent.kernel.error;

/** Provider 根据其安全策略拒绝了内容；Provider Payload 仅保留在 Cause 中。 */
public final class ModelSafetyRejectedException extends RuntimeException {
  public ModelSafetyRejectedException(Throwable cause) {
    super("模型内容安全审查拒绝请求", cause);
  }
}
