package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import java.util.Objects;

public record SideEffectIdentity(
    String idempotencyKey,
    String approvalId,
    String turnId,
    String callId,
    String toolName,
    String toolVersion,
    String fingerprint) {
  public SideEffectIdentity {
    idempotencyKey = required(idempotencyKey, "幂等键");
    approvalId = required(approvalId, "Approval ID");
    turnId = required(turnId, "Turn ID");
    callId = required(callId, "Call ID");
    toolName = required(toolName, "Tool 名称");
    toolVersion = required(toolVersion, "Tool 版本");
    fingerprint = required(fingerprint, "Fingerprint");
  }

  public static SideEffectIdentity from(ApprovalRequest request) {
    Objects.requireNonNull(request, "request");
    return new SideEffectIdentity(
        request.idempotencyKey(),
        request.approvalId(),
        request.turnId(),
        request.callId(),
        request.toolName(),
        request.toolVersion(),
        request.fingerprint());
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
