package io.namei.agent.application;

import io.namei.agent.kernel.channel.TurnFailureCode;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.error.SessionPersistenceException;
import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;

final class TurnFailureClassifier {
  private TurnFailureClassifier() {}

  static TurnFailureCode classify(RuntimeException failure) {
    if (failure instanceof SessionLockTimeoutException) {
      return TurnFailureCode.SESSION_BUSY;
    }
    if (failure instanceof ModelTimeoutException) {
      return TurnFailureCode.MODEL_TIMEOUT;
    }
    if (failure instanceof ModelInvocationException) {
      return TurnFailureCode.MODEL_UNAVAILABLE;
    }
    if (failure instanceof InvalidModelResponseException) {
      return TurnFailureCode.INVALID_MODEL_RESPONSE;
    }
    if (failure instanceof ToolCallLimitExceededException
        || failure instanceof ToolLoopLimitExceededException) {
      return TurnFailureCode.TURN_LIMIT_EXCEEDED;
    }
    if (failure instanceof MemoryContextUnavailableException) {
      return TurnFailureCode.CONTEXT_UNAVAILABLE;
    }
    if (failure instanceof ApprovalUnavailableException) {
      return TurnFailureCode.APPROVAL_UNAVAILABLE;
    }
    if (failure instanceof SideEffectStateUnknownException) {
      return TurnFailureCode.SIDE_EFFECT_STATE_UNKNOWN;
    }
    if (failure instanceof SessionPersistenceException) {
      return TurnFailureCode.PERSISTENCE_FAILED;
    }
    return TurnFailureCode.INTERNAL_ERROR;
  }
}
