package io.namei.agent.bootstrap.control;

import java.time.Instant;
import java.util.Objects;

public record OperatorSessionCreated(String accessToken, String tokenType, Instant expiresAt) {
  public OperatorSessionCreated {
    Objects.requireNonNull(accessToken, "accessToken");
    if (!"Bearer".equals(tokenType)) {
      throw new IllegalArgumentException("控制面 Token Type 无效");
    }
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  @Override
  public String toString() {
    return "OperatorSessionCreated[accessToken=<redacted>, tokenType=Bearer, expiresAt="
        + expiresAt
        + "]";
  }
}
