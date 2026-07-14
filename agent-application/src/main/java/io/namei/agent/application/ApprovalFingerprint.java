package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

final class ApprovalFingerprint {
  private ApprovalFingerprint() {}

  static String argumentsHash(Map<String, ?> arguments) {
    return sha256(CanonicalArguments.encode(arguments));
  }

  static String argumentsHashJson(String argumentsJson) {
    return sha256(CanonicalArguments.encodeJson(argumentsJson));
  }

  static String sessionBinding(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return sha256(sessionId.getBytes(StandardCharsets.UTF_8));
  }

  static String calculate(
      String sessionBinding,
      String turnId,
      String callId,
      String toolName,
      String toolVersion,
      ToolRisk risk,
      String argumentsHash,
      String idempotencyKey,
      Instant issuedAt,
      Instant expiresAt) {
    Objects.requireNonNull(risk, "risk");
    Objects.requireNonNull(issuedAt, "issuedAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        write(ApprovalRequest.FINGERPRINT_VERSION, output);
        write(sessionBinding, output);
        write(turnId, output);
        write(callId, output);
        write(toolName, output);
        write(toolVersion, output);
        write(risk.name(), output);
        write(argumentsHash, output);
        write(idempotencyKey, output);
        write(issuedAt.toString(), output);
        write(expiresAt.toString(), output);
      }
      return sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("无法生成审批 Fingerprint", exception);
    }
  }

  private static void write(String value, DataOutputStream output) throws IOException {
    Objects.requireNonNull(value, "fingerprint field");
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK 缺少 SHA-256", exception);
    }
  }
}
