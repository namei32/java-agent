package io.namei.agent.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** 解码持久胶囊前作为 AES-GCM AAD 认证的非敏感字段。 */
public record PendingOperationCapsuleBinding(
    PendingOperationReference reference, String approvalFingerprint, String toolVersion) {
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  public PendingOperationCapsuleBinding {
    reference = Objects.requireNonNull(reference, "reference");
    approvalFingerprint = requireSha256(approvalFingerprint, "Approval Fingerprint");
    toolVersion = required(toolVersion, "Tool 版本");
  }

  public static PendingOperationCapsuleBinding from(PendingOperation operation) {
    Objects.requireNonNull(operation, "operation");
    return new PendingOperationCapsuleBinding(
        operation.reference(),
        operation.approval().fingerprint(),
        operation.approval().toolVersion());
  }

  private static String requireSha256(String value, String field) {
    String normalized = required(value, field);
    if (!SHA_256.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " 必须为 SHA-256");
    }
    return normalized;
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
