package io.namei.agent.bootstrap.control;

import java.time.Instant;
import java.util.Objects;

public record OperatorSessionPrincipal(String actorRef, Instant expiresAt) {
  public OperatorSessionPrincipal {
    Objects.requireNonNull(actorRef, "actorRef");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  @Override
  public String toString() {
    return "OperatorSessionPrincipal[actorRef=<redacted>, expiresAt=" + expiresAt + "]";
  }
}
