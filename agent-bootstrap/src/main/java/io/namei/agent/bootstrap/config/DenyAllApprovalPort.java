package io.namei.agent.bootstrap.config;

import io.namei.agent.application.ApprovalPort;
import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.approval.ApprovalRequest;
import java.time.Clock;
import java.util.Objects;

public final class DenyAllApprovalPort implements ApprovalPort {
  private final Clock clock;

  public DenyAllApprovalPort(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ApprovalDecision decide(ApprovalRequest request) {
    return ApprovalDecision.deniedFor(request, clock.instant(), "production-deny-all");
  }
}
