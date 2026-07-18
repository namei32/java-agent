package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class PendingOperationGoldenTest {
  private static final Instant ISSUED = Instant.parse("2026-07-18T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void executesEveryVersionedPendingOperationFixtureCaseAgainstTheStateMachine() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/pending-operation-v1.json").toFile());
    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asText()).isEqualTo("java-contract");
    assertThat(fixture.path("contract").asText()).isEqualTo("pending-operation-v1");
    assertThat(fixture.path("cases").size()).isEqualTo(44);
    for (JsonNode testCase : fixture.path("cases")) {
      String id = testCase.path("id").asText();
      if (!id.startsWith("capsule-")
          && !id.startsWith("operation-store-")
          && !id.startsWith("operation-ledger-")
          && !id.startsWith("session-conditional-")
          && !id.startsWith("anchor-")) {
        verify(id);
      }
    }
  }

  private static void verify(String id) {
    PendingOperation pending = pending(1);
    switch (id) {
      case "reference-accepts-128-bit" ->
          assertThat(PendingOperationReference.of(reference(2)).value()).isEqualTo(reference(2));
      case "reference-rejects-short" ->
          assertThatIllegalArgumentException()
              .isThrownBy(() -> PendingOperationReference.of("not-a-reference"));
      case "reference-to-string-redacts" ->
          assertThat(PendingOperationReference.of(reference(3)).toString())
              .doesNotContain(reference(3))
              .contains("<redacted>");
      case "pending-requires-non-negative-session-revision" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () ->
                      PendingOperation.pending(
                          PendingOperationReference.of(reference(4)), request(), -1, ISSUED));
      case "pending-is-initial-state" ->
          assertThat(pending.state()).isEqualTo(PendingOperationState.PENDING_APPROVAL);
      case "pending-approves-once" ->
          assertThat(
                  pending
                      .transitionTo(
                          PendingOperationState.APPROVED_PENDING_RESUME, ISSUED.plusSeconds(1))
                      .state())
              .isEqualTo(PendingOperationState.APPROVED_PENDING_RESUME);
      case "cancelled-beats-late-approval" -> {
        PendingOperation cancelled =
            pending.transitionTo(PendingOperationState.CANCELLED, ISSUED.plusSeconds(1));
        assertThatThrownBy(
                () ->
                    cancelled.transitionTo(
                        PendingOperationState.APPROVED_PENDING_RESUME, ISSUED.plusSeconds(2)))
            .isInstanceOf(IllegalStateException.class);
      }
      case "expired-is-terminal" ->
          assertThat(
                  pending
                      .transitionTo(PendingOperationState.EXPIRED, ISSUED.plusSeconds(300))
                      .isTerminal())
              .isTrue();
      case "newer-session-is-terminal" ->
          assertThat(
                  pending
                      .transitionTo(PendingOperationState.STALE_SESSION, ISSUED.plusSeconds(1))
                      .isTerminal())
              .isTrue();
      case "approval-must-consume-before-success" ->
          assertThatThrownBy(
                  () ->
                      pending.transitionTo(PendingOperationState.SUCCEEDED, ISSUED.plusSeconds(1)))
              .isInstanceOf(IllegalStateException.class);
      case "consuming-may-succeed" ->
          assertThat(
                  pending
                      .transitionTo(
                          PendingOperationState.APPROVED_PENDING_RESUME, ISSUED.plusSeconds(1))
                      .transitionTo(PendingOperationState.CONSUMING, ISSUED.plusSeconds(2))
                      .transitionTo(PendingOperationState.SUCCEEDED, ISSUED.plusSeconds(3))
                      .state())
              .isEqualTo(PendingOperationState.SUCCEEDED);
      case "unknown-is-terminal" ->
          assertThat(
                  pending
                      .transitionTo(
                          PendingOperationState.APPROVED_PENDING_RESUME, ISSUED.plusSeconds(1))
                      .transitionTo(PendingOperationState.CONSUMING, ISSUED.plusSeconds(2))
                      .transitionTo(PendingOperationState.UNKNOWN, ISSUED.plusSeconds(3))
                      .isTerminal())
              .isTrue();
      case "commit-unreported-follows-known-success" ->
          assertThat(
                  pending
                      .transitionTo(
                          PendingOperationState.APPROVED_PENDING_RESUME, ISSUED.plusSeconds(1))
                      .transitionTo(PendingOperationState.CONSUMING, ISSUED.plusSeconds(2))
                      .transitionTo(PendingOperationState.SUCCEEDED, ISSUED.plusSeconds(3))
                      .transitionTo(PendingOperationState.COMMIT_UNREPORTED, ISSUED.plusSeconds(4))
                      .isTerminal())
              .isTrue();
      case "terminal-state-cannot-reopen" -> {
        PendingOperation denied =
            pending.transitionTo(PendingOperationState.DENIED, ISSUED.plusSeconds(1));
        assertThatThrownBy(
                () ->
                    denied.transitionTo(
                        PendingOperationState.PENDING_APPROVAL, ISSUED.plusSeconds(2)))
            .isInstanceOf(IllegalStateException.class);
      }
      default -> throw new AssertionError("未知 Pending Operation Fixture Case: " + id);
    }
  }

  private static PendingOperation pending(int reference) {
    return PendingOperation.pending(
        PendingOperationReference.of(reference(reference)), request(), 0, ISSUED);
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

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
