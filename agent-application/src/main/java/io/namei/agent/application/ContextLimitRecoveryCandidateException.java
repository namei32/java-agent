package io.namei.agent.application;

import io.namei.agent.kernel.error.ModelContextLimitException;
import java.util.Objects;

/** 仅供内部使用的信号：在任何 Tool 执行前发生了同步 Provider 上下文失败。 */
final class ContextLimitRecoveryCandidateException extends RuntimeException {
  private final ModelContextLimitException original;

  ContextLimitRecoveryCandidateException(ModelContextLimitException original) {
    super(Objects.requireNonNull(original, "original"));
    this.original = original;
  }

  ModelContextLimitException original() {
    return original;
  }
}
