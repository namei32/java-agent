package io.namei.agent.kernel.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * 审批主体对某个精确工具调用作出的不可变决定。
 *
 * @param approvalId 被决定的审批请求标识
 * @param fingerprint 与原请求一致的调用指纹，防止决定被挪用
 * @param status 批准、拒绝、过期或取消
 * @param decidedAt 决定发生时间
 * @param actorReference 审批主体的安全引用，不应保存凭据
 */
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

  /**
   * 验证该决定现在是否仍可授权指定请求。
   *
   * <p>只有批准状态、审批 ID 与指纹完全相同，且决定和检查时间都位于请求有效期内时才返回 {@code true}。
   */
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
