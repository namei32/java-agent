package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApprovalInboxResolutionTest {
  private static final Instant ISSUED = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void resolvedResultRequiresTheRequestedTerminalStateAndEntry() {
    ApprovalInboxEntry approved =
        new ApprovalInboxEntry(
            ApprovalInboxReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
            request(),
            ApprovalState.APPROVED,
            ISSUED.plusSeconds(1),
            "local-operator");

    ApprovalInboxResolution result = ApprovalInboxResolution.resolved(approved);

    assertThat(result.status()).isEqualTo(ApprovalInboxResolutionStatus.RESOLVED);
    assertThat(result.entry()).contains(approved);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ApprovalInboxResolution.resolved(pending()));
  }

  @Test
  void terminalConflictAndNotFoundCannotPretendToBeSuccessful() {
    ApprovalInboxResolution missing = ApprovalInboxResolution.notFound();
    ApprovalInboxResolution expired = ApprovalInboxResolution.expired(expired());

    assertThat(missing.entry()).isEmpty();
    assertThat(expired.status()).isEqualTo(ApprovalInboxResolutionStatus.EXPIRED);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ApprovalInboxResolution.expired(pending()));
  }

  private static ApprovalInboxEntry pending() {
    return ApprovalInboxEntry.pending(
        ApprovalInboxReference.of("AAAAAAAAAAAAAAAAAAAAAA"), request());
  }

  private static ApprovalInboxEntry expired() {
    return new ApprovalInboxEntry(
        ApprovalInboxReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
        request(),
        ApprovalState.EXPIRED,
        ISSUED.plusSeconds(300),
        "");
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
