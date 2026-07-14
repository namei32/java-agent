package io.namei.agent.kernel.approval;

import java.time.Instant;
import java.util.Objects;

public record ApprovalDecision(
    String approvalId,
    String fingerprint,
    ApprovalDecisionStatus status,
    Instant decidedAt,
    String actorReference) {
  public ApprovalDecision {
    approvalId = required(approvalId, "Approval ID");
    fingerprint = required(fingerprint, "Fingerprint");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(decidedAt, "decidedAt");
    actorReference = required(actorReference, "审批主体引用");
  }

  public static ApprovalDecision approvedFor(
      ApprovalRequest request, Instant decidedAt, String actorReference) {
    return forRequest(request, ApprovalDecisionStatus.APPROVED, decidedAt, actorReference);
  }

  public static ApprovalDecision deniedFor(
      ApprovalRequest request, Instant decidedAt, String actorReference) {
    return forRequest(request, ApprovalDecisionStatus.DENIED, decidedAt, actorReference);
  }

  public static ApprovalDecision expiredFor(
      ApprovalRequest request, Instant decidedAt, String actorReference) {
    return forRequest(request, ApprovalDecisionStatus.EXPIRED, decidedAt, actorReference);
  }

  public static ApprovalDecision cancelledFor(
      ApprovalRequest request, Instant decidedAt, String actorReference) {
    return forRequest(request, ApprovalDecisionStatus.CANCELLED, decidedAt, actorReference);
  }

  public boolean isValidApprovalFor(ApprovalRequest request, Instant checkedAt) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(checkedAt, "checkedAt");
    return status == ApprovalDecisionStatus.APPROVED
        && approvalId.equals(request.approvalId())
        && fingerprint.equals(request.fingerprint())
        && !decidedAt.isBefore(request.issuedAt())
        && decidedAt.isBefore(request.expiresAt())
        && checkedAt.isBefore(request.expiresAt());
  }

  private static ApprovalDecision forRequest(
      ApprovalRequest request,
      ApprovalDecisionStatus status,
      Instant decidedAt,
      String actorReference) {
    Objects.requireNonNull(request, "request");
    return new ApprovalDecision(
        request.approvalId(), request.fingerprint(), status, decidedAt, actorReference);
  }

  private static String required(String value, String field) {
    Objects.requireNonNull(value, field);
    var normalized = value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}
