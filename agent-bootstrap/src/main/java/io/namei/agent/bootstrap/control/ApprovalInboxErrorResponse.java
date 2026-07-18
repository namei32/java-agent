package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlPlaneContract;
import java.util.Objects;

record ApprovalInboxErrorResponse(
    int schemaVersion, String code, boolean retryable, String requestId) {
  static ApprovalInboxErrorResponse of(String code, boolean retryable, String requestId) {
    return new ApprovalInboxErrorResponse(
        ControlPlaneContract.CURRENT_VERSION,
        Objects.requireNonNull(code, "code"),
        retryable,
        Objects.requireNonNull(requestId, "requestId"));
  }
}
