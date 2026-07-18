package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PendingOperationTest {
  private static final Instant ISSUED = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void acceptsOnlyOpaque128BitReferencesAndRedactsThem() {
    PendingOperationReference reference = PendingOperationReference.of(reference(1));

    assertThat(reference.value()).isEqualTo(reference(1));
    assertThat(reference.toString()).doesNotContain(reference(1)).contains("<redacted>");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PendingOperationReference.of("pending-operation"));
  }

  @Test
  void startsPendingAndRequiresANonNegativeSessionRevision() {
    PendingOperation operation =
        PendingOperation.pending(PendingOperationReference.of(reference(2)), request(), 4, ISSUED);

    assertThat(operation.state()).isEqualTo(PendingOperationState.PENDING_APPROVAL);
    assertThat(operation.stateChangedAt()).isEqualTo(ISSUED);
    assertThat(operation.toString())
        .doesNotContain("approval-id", "session-binding", "turn-id", "call-id")
        .contains("<redacted>");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PendingOperation.pending(
                    PendingOperationReference.of(reference(3)), request(), -1, ISSUED));
  }

  @Test
  void permitsOnlyTheApprovedConsumptionAndKnownSuccessPath() {
    PendingOperation pending =
        PendingOperation.pending(PendingOperationReference.of(reference(4)), request(), 0, ISSUED);

    PendingOperation approved =
        pending.transitionTo(PendingOperationState.APPROVED_PENDING_RESUME, ISSUED.plusSeconds(1));
    PendingOperation consuming =
        approved.transitionTo(PendingOperationState.CONSUMING, ISSUED.plusSeconds(2));
    PendingOperation succeeded =
        consuming.transitionTo(PendingOperationState.SUCCEEDED, ISSUED.plusSeconds(3));
    PendingOperation unreported =
        succeeded.transitionTo(PendingOperationState.COMMIT_UNREPORTED, ISSUED.plusSeconds(4));

    assertThat(unreported.isTerminal()).isTrue();
    assertThatThrownBy(
            () -> unreported.transitionTo(PendingOperationState.SUCCEEDED, ISSUED.plusSeconds(5)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void givesCancellationExpiryAndNewerSessionPrecedenceBeforeConsumption() {
    PendingOperation pending =
        PendingOperation.pending(PendingOperationReference.of(reference(5)), request(), 0, ISSUED);

    PendingOperation cancelled =
        pending.transitionTo(PendingOperationState.CANCELLED, ISSUED.plusSeconds(1));
    assertThatThrownBy(
            () ->
                cancelled.transitionTo(
                    PendingOperationState.APPROVED_PENDING_RESUME, ISSUED.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            PendingOperation.pending(
                    PendingOperationReference.of(reference(6)), request(), 0, ISSUED)
                .transitionTo(PendingOperationState.EXPIRED, ISSUED.plusSeconds(300))
                .isTerminal())
        .isTrue();
    assertThat(
            PendingOperation.pending(
                    PendingOperationReference.of(reference(7)), request(), 0, ISSUED)
                .transitionTo(PendingOperationState.STALE_SESSION, ISSUED.plusSeconds(1))
                .isTerminal())
        .isTrue();
  }

  @Test
  void doesNotAllowAnyPreConsumptionPathToExecuteOrCommitUnreported() {
    PendingOperation pending =
        PendingOperation.pending(PendingOperationReference.of(reference(8)), request(), 0, ISSUED);
    PendingOperation approved =
        pending.transitionTo(PendingOperationState.APPROVED_PENDING_RESUME, ISSUED.plusSeconds(1));

    assertThatThrownBy(
            () -> approved.transitionTo(PendingOperationState.SUCCEEDED, ISSUED.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                pending.transitionTo(
                    PendingOperationState.COMMIT_UNREPORTED, ISSUED.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ApprovalRequest request() {
    return new ApprovalRequest(
        "approval-id",
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

  private static String reference(int value) {
    byte[] bytes = new byte[16];
    bytes[14] = (byte) (value >>> 8);
    bytes[15] = (byte) value;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
