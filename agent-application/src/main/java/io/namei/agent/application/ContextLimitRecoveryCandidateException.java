package io.namei.agent.application;

import io.namei.agent.kernel.error.ModelContextLimitException;
import java.util.Objects;

/**
 * Internal-only signal: a synchronous Provider context failure occurred before any Tool execution.
 */
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
