package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ApprovalInboxDecision;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.application.ApprovalInboxResolutionStatus;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcApprovalInboxTest {
  private static final Instant ISSUED = Instant.parse("2026-07-18T00:00:00Z");

  @TempDir Path tempDir;

  @Test
  void persistsAOneTimeOperatorDecisionAcrossRepositoryReopen() {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcApprovalInbox inbox = new JdbcApprovalInbox(schema);
    ApprovalInboxEntry pending = pending("AAAAAAAAAAAAAAAAAAAAAA", "approval-1");

    assertThat(inbox.create(pending)).isEqualTo(pending);
    assertThat(
            inbox
                .resolve(
                    pending.reference(),
                    ApprovalInboxDecision.APPROVED,
                    "operator-ref",
                    ISSUED.plusSeconds(1))
                .status())
        .isEqualTo(ApprovalInboxResolutionStatus.RESOLVED);

    JdbcApprovalInbox reopened = new JdbcApprovalInbox(schema());
    ApprovalInboxEntry stored = reopened.list(ISSUED.plusSeconds(2), 64).getFirst();
    assertThat(stored.state()).isEqualTo(ApprovalState.APPROVED);
    assertThat(stored.actorReference()).isEqualTo("operator-ref");
    assertThat(stored.request().argumentsHash()).isEqualTo("b".repeat(64));
  }

  @Test
  void resolveAtomicallyExpiresInsteadOfAcceptingALateDecision() {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcApprovalInbox inbox = new JdbcApprovalInbox(schema);
    ApprovalInboxEntry pending = pending("AQEBAQEBAQEBAQEBAQEBAQ", "approval-2");
    inbox.create(pending);

    assertThat(
            inbox
                .resolve(
                    pending.reference(),
                    ApprovalInboxDecision.DENIED,
                    "operator-ref",
                    ISSUED.plusSeconds(300))
                .status())
        .isEqualTo(ApprovalInboxResolutionStatus.EXPIRED);
    assertThat(inbox.list(ISSUED.plusSeconds(300), 64).getFirst().state())
        .isEqualTo(ApprovalState.EXPIRED);
  }

  @Test
  void concurrentResolveLetsExactlyOneOperatorConsumeThePendingDecision() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcApprovalInbox inbox = new JdbcApprovalInbox(schema);
    ApprovalInboxEntry pending = pending("AgICAgICAgICAgICAgICAg", "approval-3");
    inbox.create(pending);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var approved =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return inbox
                    .resolve(
                        pending.reference(),
                        ApprovalInboxDecision.APPROVED,
                        "operator-one",
                        ISSUED.plusSeconds(1))
                    .status();
              });
      var denied =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return inbox
                    .resolve(
                        pending.reference(),
                        ApprovalInboxDecision.DENIED,
                        "operator-two",
                        ISSUED.plusSeconds(1))
                    .status();
              });

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(java.util.List.of(approved.get(), denied.get()))
          .containsExactlyInAnyOrder(
              ApprovalInboxResolutionStatus.RESOLVED,
              ApprovalInboxResolutionStatus.ALREADY_RESOLVED);
    }
  }

  @Test
  @Tag("failure")
  void fullInboxFailsClosedRatherThanDroppingAnExistingDecision() {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcApprovalInbox inbox = new JdbcApprovalInbox(schema);
    for (int index = 0; index < 64; index++) {
      inbox.create(pending(reference(index), "approval-capacity-" + index));
    }

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> inbox.create(pending(reference(64), "approval-capacity-64")))
        .isInstanceOf(ApprovalInboxRepositoryException.class);
    assertThat(inbox.list(ISSUED, 64)).hasSize(64);
  }

  private ApprovalInboxSchemaInitializer schema() {
    return new ApprovalInboxSchemaInitializer(tempDir.resolve("approval-inbox.db"), 5_000);
  }

  private static ApprovalInboxEntry pending(String reference, String approvalId) {
    return ApprovalInboxEntry.pending(
        ApprovalInboxReference.of(reference),
        new ApprovalRequest(
            approvalId,
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
            "a".repeat(64)));
  }

  private static String reference(int value) {
    byte[] bytes = new byte[16];
    bytes[14] = (byte) (value >>> 8);
    bytes[15] = (byte) value;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
