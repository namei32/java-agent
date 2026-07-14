package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.approval.ApprovalRequest;

@FunctionalInterface
public interface ApprovalPort {
  ApprovalDecision decide(ApprovalRequest request);
}
