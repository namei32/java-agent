package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlPlaneContract;

public record ControlErrorResponse(
    int schemaVersion, String code, boolean retryable, String requestId) {
  public static ControlErrorResponse of(
      io.namei.agent.kernel.control.ControlStableCode code, String requestId) {
    return new ControlErrorResponse(
        ControlPlaneContract.CURRENT_VERSION, code.name(), code.retryable(), requestId);
  }
}
