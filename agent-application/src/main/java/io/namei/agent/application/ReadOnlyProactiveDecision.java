package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveDeliveryBoundary;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe P1 projection. It deliberately cannot contain Source/Drift text, an approval reference,
 * target identity, message body, or a delivery capability.
 */
public record ReadOnlyProactiveDecision(Kind kind, Optional<ProactiveStableCode> code) {
  public enum Kind {
    SKIPPED,
    PENDING_APPROVAL,
    CANCELLED
  }

  public ReadOnlyProactiveDecision {
    kind = Objects.requireNonNull(kind, "kind");
    code = Objects.requireNonNull(code, "code");
    if ((kind == Kind.SKIPPED && code.isEmpty()) || (kind != Kind.SKIPPED && code.isPresent())) {
      throw new IllegalArgumentException("只读主动决策的状态与稳定码不匹配");
    }
  }

  public static ReadOnlyProactiveDecision skipped(ProactiveStableCode code) {
    return new ReadOnlyProactiveDecision(
        Kind.SKIPPED, Optional.of(Objects.requireNonNull(code, "code")));
  }

  public static ReadOnlyProactiveDecision pendingApproval() {
    return new ReadOnlyProactiveDecision(Kind.PENDING_APPROVAL, Optional.empty());
  }

  public static ReadOnlyProactiveDecision cancelled() {
    return new ReadOnlyProactiveDecision(Kind.CANCELLED, Optional.empty());
  }

  public ProactiveDeliveryBoundary deliveryBoundary() {
    return new ProactiveDeliveryBoundary(
        kind == Kind.PENDING_APPROVAL
            ? ProactiveDeliveryBoundary.Disposition.PENDING_APPROVAL
            : ProactiveDeliveryBoundary.Disposition.NOT_REQUESTED);
  }

  /** P1 has no Memory port, so a projection cannot cause a mutation. */
  public int memoryMutationCount() {
    return 0;
  }
}
