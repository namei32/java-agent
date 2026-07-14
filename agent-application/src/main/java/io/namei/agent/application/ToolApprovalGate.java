package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.approval.ApprovalDecisionStatus;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Instant;
import java.util.Objects;

final class ToolApprovalGate {
  ApprovalRequest request(
      SideEffectBatchCoordinator.Context context,
      ToolCall call,
      ToolDefinition definition,
      ToolRisk risk,
      String approvalId,
      String idempotencyKey,
      Instant issuedAt,
      Instant expiresAt) {
    String argumentsHash = ApprovalFingerprint.argumentsHash(call.arguments());
    String fingerprint =
        ApprovalFingerprint.calculate(
            context.sessionBinding(),
            context.turnId(),
            call.id(),
            call.name(),
            definition.version(),
            risk,
            argumentsHash,
            idempotencyKey,
            issuedAt,
            expiresAt);
    return new ApprovalRequest(
        approvalId,
        context.sessionBinding(),
        context.turnId(),
        call.id(),
        call.name(),
        definition.version(),
        risk,
        argumentsHash,
        idempotencyKey,
        "工具 " + definition.name() + " 请求执行副作用。",
        issuedAt,
        expiresAt,
        ApprovalRequest.FINGERPRINT_VERSION,
        fingerprint);
  }

  ApprovalDecisionStatus resolve(
      ApprovalRequest request, ApprovalDecision decision, Instant checkedAt) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(checkedAt, "checkedAt");
    if (decision == null
        || !request.approvalId().equals(decision.approvalId())
        || !request.fingerprint().equals(decision.fingerprint())
        || decision.decidedAt().isBefore(request.issuedAt())
        || !decision.decidedAt().isBefore(request.expiresAt())
        || !checkedAt.isBefore(request.expiresAt())) {
      throw new ApprovalUnavailableException();
    }
    if (decision.status() == ApprovalDecisionStatus.APPROVED
        && !decision.isValidApprovalFor(request, checkedAt)) {
      throw new ApprovalUnavailableException();
    }
    return decision.status();
  }

  void revalidate(
      ApprovalRequest request,
      SideEffectBatchCoordinator.Context context,
      ToolCall call,
      ToolDefinition definition,
      ToolRisk risk,
      Instant checkedAt) {
    String currentArgumentsHash = ApprovalFingerprint.argumentsHash(call.arguments());
    String currentFingerprint =
        ApprovalFingerprint.calculate(
            context.sessionBinding(),
            context.turnId(),
            call.id(),
            call.name(),
            definition.version(),
            risk,
            currentArgumentsHash,
            request.idempotencyKey(),
            request.issuedAt(),
            request.expiresAt());
    if (!request.argumentsHash().equals(currentArgumentsHash)
        || !request.fingerprint().equals(currentFingerprint)
        || !checkedAt.isBefore(request.expiresAt())) {
      throw new ApprovalUnavailableException();
    }
  }
}
