package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApprovalInboxEntryTest {
  private static final Instant ISSUED = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void pendingEntryKeepsInternalBindingButRedactsItFromToString() {
    ApprovalInboxEntry entry = ApprovalInboxEntry.pending(reference(), request());

    assertThat(entry.reference().value()).isEqualTo("AAAAAAAAAAAAAAAAAAAAAA");
    assertThat(entry.request().fingerprint()).isEqualTo("a".repeat(64));
    assertThat(entry.state()).isEqualTo(ApprovalState.PENDING);
    assertThat(entry.decidedAt()).isNull();
    assertThat(entry.actorReference()).isBlank();
    assertThat(entry.toString())
        .doesNotContain(entry.request().approvalId())
        .doesNotContain(entry.request().fingerprint())
        .doesNotContain(entry.request().argumentsHash())
        .doesNotContain(entry.request().idempotencyKey())
        .contains("<redacted>");
  }

  @Test
  void referenceRequiresExactlySixteenRandomBytesInBase64UrlForm() {
    assertThatIllegalArgumentException().isThrownBy(() -> ApprovalInboxReference.of("too-short"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ApprovalInboxReference.of("AAAAAAAAAAAAAAAAAAAAA+"));
  }

  private static ApprovalInboxReference reference() {
    return ApprovalInboxReference.of("AAAAAAAAAAAAAAAAAAAAAA");
  }

  private static ApprovalRequest request() {
    return new ApprovalRequest(
        "approval-opaque-id",
        "session-binding",
        "turn-id",
        "call-id",
        "safe_write",
        "v1",
        ToolRisk.WRITE,
        "b".repeat(64),
        "idempotency-key",
        "安全摘要",
        ISSUED,
        ISSUED.plusSeconds(300),
        ApprovalRequest.FINGERPRINT_VERSION,
        "a".repeat(64));
  }
}
