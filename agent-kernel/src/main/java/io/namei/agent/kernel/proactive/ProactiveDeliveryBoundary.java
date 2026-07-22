package io.namei.agent.kernel.proactive;

import java.util.Objects;

/** P0 可以暴露 Approval 请求，但没有授权或执行投递的路径。Transport 接线仍属于后续单独批准的 Capability。 */
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
