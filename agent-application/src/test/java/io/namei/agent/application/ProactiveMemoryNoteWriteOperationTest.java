package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProactiveMemoryNoteWriteOperationTest {
  private static final Instant ISSUED_AT = Instant.parse("2026-07-20T06:00:00Z");
  private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(300);
  private static final ProactiveMemoryNoteWriteOperationReference REFERENCE =
      ProactiveMemoryNoteWriteOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA");
  private static final ProactiveMemoryNoteWriteAnchor ANCHOR =
      ProactiveMemoryNoteWriteAnchor.pending(
          REFERENCE, ProactiveJobRef.parse("daily-summary"), "a".repeat(64));
  private static final ApprovalRequest APPROVAL =
      new ApprovalRequest(
          "approval-fixture",
          "b".repeat(64),
          "turn-fixture",
          "call-fixture",
          ProactiveMemoryNoteWriteCapability.CAPABILITY_NAME,
          ProactiveMemoryNoteWriteCapability.CAPABILITY_VERSION,
          ToolRisk.WRITE,
          "c".repeat(64),
          "idempotency-fixture",
          "请求保存本地主动记忆候选。",
          ISSUED_AT,
          EXPIRES_AT,
          ApprovalRequest.FINGERPRINT_VERSION,
          "d".repeat(64));

  @Test
  void startsPendingWithP6SpecificAnchorAndRedactsItsSensitiveBindings() {
    var operation =
        ProactiveMemoryNoteWriteOperation.pending(REFERENCE, APPROVAL, ANCHOR, ISSUED_AT);

    assertThat(operation.state())
        .isEqualTo(ProactiveMemoryNoteWriteOperation.State.PENDING_APPROVAL);
    assertThat(operation.isTerminal()).isFalse();
    assertThat(operation.toString())
        .doesNotContain(REFERENCE.value(), ANCHOR.targetHash(), APPROVAL.fingerprint());
    assertThat(ANCHOR.toString()).doesNotContain(REFERENCE.value(), ANCHOR.targetHash());
  }

  @Test
  void permitsOnlyTheApprovedOneWayRecoveryStates() {
    var pending = ProactiveMemoryNoteWriteOperation.pending(REFERENCE, APPROVAL, ANCHOR, ISSUED_AT);
    var approved =
        pending.transition(
            ProactiveMemoryNoteWriteOperation.State.APPROVED_PENDING_RESUME,
            ISSUED_AT.plusSeconds(1));
    var consuming =
        approved.transition(
            ProactiveMemoryNoteWriteOperation.State.CONSUMING, ISSUED_AT.plusSeconds(2));
    var succeeded =
        consuming.transition(
            ProactiveMemoryNoteWriteOperation.State.SUCCEEDED, ISSUED_AT.plusSeconds(3));

    assertThat(succeeded.isTerminal()).isTrue();
    assertThat(
            succeeded
                .transition(
                    ProactiveMemoryNoteWriteOperation.State.COMMIT_UNREPORTED,
                    ISSUED_AT.plusSeconds(4))
                .state())
        .isEqualTo(ProactiveMemoryNoteWriteOperation.State.COMMIT_UNREPORTED);
    assertThatThrownBy(
            () ->
                pending.transition(
                    ProactiveMemoryNoteWriteOperation.State.SUCCEEDED, ISSUED_AT.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void exposesOnlyCreatedOrReinforcedSafeReceiptsAndOneWayAnchorTransitions() {
    assertThat(new ProactiveMemoryNoteWriteSafeReceipt("CREATED").code()).isEqualTo("CREATED");
    assertThat(new ProactiveMemoryNoteWriteSafeReceipt("REINFORCED").code())
        .isEqualTo("REINFORCED");
    assertThatThrownBy(() -> new ProactiveMemoryNoteWriteSafeReceipt("UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ANCHOR.transition(ProactiveMemoryNoteWriteAnchor.State.COMMITTED).state())
        .isEqualTo(ProactiveMemoryNoteWriteAnchor.State.COMMITTED);
    assertThatThrownBy(
            () -> ANCHOR.transition(ProactiveMemoryNoteWriteAnchor.State.PENDING_APPROVAL))
        .isInstanceOf(IllegalStateException.class);
  }
}
