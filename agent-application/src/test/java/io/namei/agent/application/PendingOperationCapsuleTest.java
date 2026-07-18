package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PendingOperationCapsuleTest {
  private static final Instant ISSUED = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void bindsSessionAndCanonicalArgumentsToTheExactPendingOperation() {
    PendingOperation operation = operation(1);
    PendingOperationCapsule capsule =
        PendingOperationCapsule.forOperation(
            operation, "session-1", "{\"value\":1,\"optional\":null}", "boundary-v1");

    assertThat(capsule.matches(operation)).isTrue();
    assertThat(capsule.toToolCall().arguments())
        .containsEntry("value", BigInteger.ONE)
        .containsEntry("optional", null);
    assertThat(capsule.toString())
        .doesNotContain("session-1", "turn-id", "call-id", "idempotency-key")
        .contains("<redacted>");
  }

  @Test
  void refusesSessionThatDoesNotMatchTheApprovalBinding() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PendingOperationCapsule.forOperation(
                    operation(2),
                    "different-session",
                    "{\"value\":1,\"optional\":null}",
                    "boundary-v1"));
  }

  private static PendingOperation operation(int reference) {
    String arguments = "{\"value\":1,\"optional\":null}";
    ApprovalRequest request =
        new ApprovalRequest(
            "approval-id",
            ApprovalFingerprint.sessionBinding("session-1"),
            "turn-id",
            "call-id",
            "safe_write",
            "v1",
            ToolRisk.WRITE,
            ApprovalFingerprint.argumentsHashJson(arguments),
            "idempotency-key",
            "安全摘要",
            ISSUED,
            ISSUED.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64));
    return PendingOperation.pending(
        PendingOperationReference.of(reference(reference)), request, 2, ISSUED);
  }

  private static String reference(int value) {
    byte[] bytes = new byte[16];
    bytes[14] = (byte) (value >>> 8);
    bytes[15] = (byte) value;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
