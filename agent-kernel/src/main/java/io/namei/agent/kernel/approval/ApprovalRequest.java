package io.namei.agent.kernel.approval;

import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record ApprovalRequest(
    String approvalId,
    String sessionBinding,
    String turnId,
    String callId,
    String toolName,
    String toolVersion,
    ToolRisk risk,
    String argumentsHash,
    String idempotencyKey,
    String summary,
    Instant issuedAt,
    Instant expiresAt,
    String fingerprintVersion,
    String fingerprint) {
  public static final String FINGERPRINT_VERSION = "approval-fingerprint-v1";
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  public ApprovalRequest {
    approvalId = required(approvalId, "Approval ID");
    sessionBinding = required(sessionBinding, "Session Binding");
    turnId = required(turnId, "Turn ID");
    callId = required(callId, "Call ID");
    toolName = required(toolName, "Tool 名称");
    toolVersion = required(toolVersion, "Tool 版本");
    Objects.requireNonNull(risk, "risk");
    if (risk == ToolRisk.READ_ONLY) {
      throw new IllegalArgumentException("只读工具不应创建审批请求");
    }
    argumentsHash = sha256(argumentsHash, "Arguments Hash");
    idempotencyKey = required(idempotencyKey, "幂等键");
    summary = required(summary, "审批摘要");
    Objects.requireNonNull(issuedAt, "issuedAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("审批过期时间必须晚于签发时间");
    }
    fingerprintVersion = required(fingerprintVersion, "Fingerprint 版本");
    if (!FINGERPRINT_VERSION.equals(fingerprintVersion)) {
      throw new IllegalArgumentException("不支持的审批 Fingerprint 版本");
    }
    fingerprint = sha256(fingerprint, "Fingerprint");
  }

  private static String required(String value, String field) {
    Objects.requireNonNull(value, field);
    var normalized = value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }

  private static String sha256(String value, String field) {
    var normalized = required(value, field);
    if (!SHA_256.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " 必须为 SHA-256");
    }
    return normalized;
  }
}
