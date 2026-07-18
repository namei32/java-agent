package io.namei.agent.kernel.proactive;

import java.util.Objects;

/**
 * P0 can expose a request for approval but has no route to authorize or perform delivery. Transport
 * wiring remains a later, separately approved capability.
 */
public record ProactiveDeliveryBoundary(Disposition disposition) {
  public enum Disposition {
    NOT_REQUESTED,
    PENDING_APPROVAL
  }

  public ProactiveDeliveryBoundary {
    disposition = Objects.requireNonNull(disposition, "disposition");
  }

  public static ProactiveDeliveryBoundary from(ProactiveDecision decision) {
    Objects.requireNonNull(decision, "decision");
    return new ProactiveDeliveryBoundary(
        decision.kind() == ProactiveDecision.Kind.REQUESTED
            ? Disposition.PENDING_APPROVAL
            : Disposition.NOT_REQUESTED);
  }

  public boolean allowsExternalDelivery() {
    return false;
  }

  public boolean transportAuthorized() {
    return false;
  }
}
