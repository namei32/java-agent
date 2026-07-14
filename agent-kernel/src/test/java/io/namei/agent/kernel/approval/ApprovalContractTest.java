package io.namei.agent.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.lifecycle.TurnEventType;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalContractTest {
  private static final Instant ISSUED_AT = Instant.parse("2026-07-14T05:00:00Z");
  private static final Instant EXPIRES_AT = Instant.parse("2026-07-14T05:05:00Z");

  @Test
  void rejectsInvalidApprovalRequestsAndRepresentsSideEffectProtocol() {
    assertThatThrownBy(() -> request("", "write_note", ISSUED_AT, EXPIRES_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("approval-1", " ", ISSUED_AT, EXPIRES_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("approval-1", "write_note", EXPIRES_AT, ISSUED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("approval-1", "write_note", ISSUED_AT, ISSUED_AT))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(ApprovalState.values())
        .containsExactly(
            ApprovalState.PENDING,
            ApprovalState.APPROVED,
            ApprovalState.CONSUMED,
            ApprovalState.DENIED,
            ApprovalState.EXPIRED,
            ApprovalState.CANCELLED);
    assertThat(SideEffectExecutionState.values())
        .containsExactly(
            SideEffectExecutionState.RESERVED,
            SideEffectExecutionState.RUNNING,
            SideEffectExecutionState.SUCCEEDED,
            SideEffectExecutionState.FAILED,
            SideEffectExecutionState.UNKNOWN);
    assertThat(
            new ToolDefinition("write_note", "写入测试替身", Map.of("type", "object"), ToolRisk.WRITE)
                .risk())
        .isEqualTo(ToolRisk.WRITE);
  }

  @Test
  void onlyTreatsAnExactlyBoundTimelyDecisionAsAValidApproval() {
    var request = request("approval-1", "write_note", ISSUED_AT, EXPIRES_AT);
    var approved =
        ApprovalDecision.approvedFor(request, ISSUED_AT.plusSeconds(30), "actor-reference");
    var wrongId =
        new ApprovalDecision(
            "approval-other",
            request.fingerprint(),
            ApprovalDecisionStatus.APPROVED,
            ISSUED_AT.plusSeconds(30),
            "actor-reference");
    var wrongFingerprint =
        new ApprovalDecision(
            request.approvalId(),
            "f".repeat(64),
            ApprovalDecisionStatus.APPROVED,
            ISSUED_AT.plusSeconds(30),
            "actor-reference");

    assertThat(approved.isValidApprovalFor(request, ISSUED_AT.plusSeconds(31))).isTrue();
    assertThat(wrongId.isValidApprovalFor(request, ISSUED_AT.plusSeconds(31))).isFalse();
    assertThat(wrongFingerprint.isValidApprovalFor(request, ISSUED_AT.plusSeconds(31))).isFalse();
    assertThat(approved.isValidApprovalFor(request, EXPIRES_AT)).isFalse();
    assertThat(
            ApprovalDecision.deniedFor(request, ISSUED_AT.plusSeconds(30), "actor-reference")
                .isValidApprovalFor(request, ISSUED_AT.plusSeconds(31)))
        .isFalse();
  }

  @Test
  void fixesApprovalAndSideEffectLifecycleFieldsWithoutSensitivePayloads() {
    var requested = TurnLifecycleEvent.approvalRequested(2, "call-9", "write_note");
    var resolved =
        TurnLifecycleEvent.approvalResolved(
            2, "call-9", "write_note", ApprovalDecisionStatus.APPROVED);
    var started = TurnLifecycleEvent.sideEffectStarted(2, "call-9", "write_note");
    var completed =
        TurnLifecycleEvent.sideEffectCompleted(2, "call-9", "write_note", ToolResultStatus.SUCCESS);

    assertThat(requested.type()).isEqualTo(TurnEventType.APPROVAL_REQUESTED);
    assertThat(resolved.status()).isEqualTo("APPROVED");
    assertThat(started.type()).isEqualTo(TurnEventType.SIDE_EFFECT_STARTED);
    assertThat(completed.status()).isEqualTo("SUCCESS");
    assertThat(
            Arrays.stream(TurnLifecycleEvent.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactly("type", "iteration", "callId", "toolName", "status")
        .doesNotContain("arguments", "result", "summary", "actor", "idempotencyKey", "fingerprint");
    assertThatThrownBy(
            () ->
                new TurnLifecycleEvent(
                    TurnEventType.APPROVAL_REQUESTED, 0, "call-9", "write_note", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TurnLifecycleEvent(
                    TurnEventType.APPROVAL_RESOLVED, 2, "call-9", "write_note", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ApprovalRequest request(
      String approvalId, String toolName, Instant issuedAt, Instant expiresAt) {
    return new ApprovalRequest(
        approvalId,
        "session-binding",
        "turn-1",
        "call-1",
        toolName,
        "v1",
        ToolRisk.WRITE,
        "a".repeat(64),
        "idempotency-1",
        "写入测试数据",
        issuedAt,
        expiresAt,
        "approval-fingerprint-v1",
        "b".repeat(64));
  }
}
