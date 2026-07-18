package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlCancellationOutcome;
import io.namei.agent.kernel.control.ControlPlaneContract;

public record ControlCancelResponse(
    int schemaVersion, String turnRef, String result, String state) {
  static ControlCancelResponse from(String turnRef, ControlCancellationOutcome outcome) {
    return new ControlCancelResponse(
        ControlPlaneContract.CURRENT_VERSION,
        turnRef,
        outcome.result().name(),
        outcome.state().map(Enum::name).orElse(null));
  }

  @Override
  public String toString() {
    return "ControlCancelResponse[schemaVersion="
        + schemaVersion
        + ", turnRef=<redacted>, result="
        + result
        + ", state="
        + state
        + "]";
  }
}
