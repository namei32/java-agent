package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlPlaneContract;
import java.time.Instant;

public record ControlSessionResponse(
    int schemaVersion, String accessToken, String tokenType, Instant expiresAt) {
  static ControlSessionResponse from(OperatorSessionCreated created) {
    return new ControlSessionResponse(
        ControlPlaneContract.CURRENT_VERSION,
        created.accessToken(),
        created.tokenType(),
        created.expiresAt());
  }
}
