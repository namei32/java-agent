package io.namei.agent.application;

import io.namei.agent.kernel.tool.ToolCall;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Sensitive, in-memory plaintext required to resume exactly one previously approved operation. */
public record PendingOperationCapsule(
    int schemaVersion,
    String sessionId,
    long expectedNextSequence,
    String turnId,
    String callId,
    String toolName,
    String toolVersion,
    String risk,
    String canonicalArgumentsJson,
    String approvalId,
    String fingerprint,
    String idempotencyKey,
    String executionBoundaryVersion) {
  public static final int SCHEMA_VERSION = 1;
  private static final Pattern BOUNDARY_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public PendingOperationCapsule {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("不支持的待审批操作胶囊版本");
    }
    sessionId = required(sessionId, "Session ID");
    if (expectedNextSequence < 0) {
      throw new IllegalArgumentException("预期 Session 序号不能为负数");
    }
    turnId = required(turnId, "Turn ID");
    callId = required(callId, "Call ID");
    toolName = required(toolName, "Tool 名称");
    toolVersion = required(toolVersion, "Tool 版本");
    risk = required(risk, "Tool 风险");
    canonicalArgumentsJson = required(canonicalArgumentsJson, "Canonical Arguments JSON");
    if (canonicalArgumentsJson.length() > 65_536) {
      throw new IllegalArgumentException("待审批操作参数超出胶囊上限");
    }
    CanonicalArguments.parseJsonObject(canonicalArgumentsJson);
    approvalId = required(approvalId, "Approval ID");
    fingerprint = required(fingerprint, "Fingerprint");
    idempotencyKey = required(idempotencyKey, "幂等键");
    executionBoundaryVersion = required(executionBoundaryVersion, "执行边界版本");
    if (!BOUNDARY_VERSION.matcher(executionBoundaryVersion).matches()) {
      throw new IllegalArgumentException("执行边界版本格式无效");
    }
  }

  public static PendingOperationCapsule forOperation(
      PendingOperation operation,
      String sessionId,
      String canonicalArgumentsJson,
      String executionBoundaryVersion) {
    Objects.requireNonNull(operation, "operation");
    var request = operation.approval();
    var capsule =
        new PendingOperationCapsule(
            SCHEMA_VERSION,
            sessionId,
            operation.expectedNextSequence(),
            request.turnId(),
            request.callId(),
            request.toolName(),
            request.toolVersion(),
            request.risk().name(),
            canonicalArgumentsJson,
            request.approvalId(),
            request.fingerprint(),
            request.idempotencyKey(),
            executionBoundaryVersion);
    if (!capsule.matches(operation)) {
      throw new IllegalArgumentException("参数胶囊与待审批操作绑定不一致");
    }
    return capsule;
  }

  public boolean matches(PendingOperation operation) {
    Objects.requireNonNull(operation, "operation");
    var request = operation.approval();
    return expectedNextSequence == operation.expectedNextSequence()
        && ApprovalFingerprint.sessionBinding(sessionId).equals(request.sessionBinding())
        && turnId.equals(request.turnId())
        && callId.equals(request.callId())
        && toolName.equals(request.toolName())
        && toolVersion.equals(request.toolVersion())
        && risk.equals(request.risk().name())
        && ApprovalFingerprint.argumentsHashJson(canonicalArgumentsJson)
            .equals(request.argumentsHash())
        && approvalId.equals(request.approvalId())
        && fingerprint.equals(request.fingerprint())
        && idempotencyKey.equals(request.idempotencyKey());
  }

  public ToolCall toToolCall() {
    Map<String, Object> arguments = CanonicalArguments.parseJsonObject(canonicalArgumentsJson);
    return new ToolCall(callId, toolName, arguments);
  }

  @Override
  public String toString() {
    return "PendingOperationCapsule[schemaVersion="
        + schemaVersion
        + ", sessionId=<redacted>, expectedNextSequence="
        + expectedNextSequence
        + ", turnId=<redacted>, callId=<redacted>, toolName="
        + toolName
        + ", toolVersion="
        + toolVersion
        + ", risk="
        + risk
        + ", canonicalArgumentsJson=<redacted>, approvalId=<redacted>, fingerprint=<redacted>, "
        + "idempotencyKey=<redacted>, executionBoundaryVersion="
        + executionBoundaryVersion
        + "]";
  }

  private static String required(String value, String field) {
    Objects.requireNonNull(value, field);
    String normalized = value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}
