package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ApprovalFingerprint;
import io.namei.agent.application.ApprovalInboxDecision;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.application.PendingOperation;
import io.namei.agent.application.PendingOperationCapsule;
import io.namei.agent.application.PendingOperationKey;
import io.namei.agent.application.PendingOperationKeyProvider;
import io.namei.agent.application.PendingOperationReference;
import io.namei.agent.application.PendingOperationState;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnAnchorState;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PendingOperationRecoveryRehearsalTest {
  private static final Instant ISSUED = Instant.parse("2026-07-19T00:00:00Z");
  private static final OffsetDateTime TURN_AT = OffsetDateTime.parse("2026-07-19T08:00:00+08:00");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OPERATION_REF = "AAAAAAAAAAAAAAAAAAAAAA";
  private static final String OTHER_REF = "AQEBAQEBAQEBAQEBAQEBAQ";

  @TempDir Path tempDir;
  private int scenarioIndex;

  @Test
  void testOnlyFakeCapabilityCommitsOnceThenCannotReplay() {
    Scenario scenario = approvedScenario(OPERATION_REF, OPERATION_REF);
    CountingFakeCapability capability = CountingFakeCapability.success();
    TestOnlyRecoveryRehearsal rehearsal = scenario.rehearsal();

    assertThat(rehearsal.run(scenario.anchor(), capability))
        .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.COMMITTED);
    assertThat(capability.invocations()).isOne();
    assertThat(scenario.operationStore().find(PendingOperationReference.of(OPERATION_REF)))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.SUCCEEDED));
    assertThat(scenario.operationStore().findLedger(PendingOperationReference.of(OPERATION_REF)))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(SideEffectExecutionState.SUCCEEDED));
    assertThat(scenario.sessions().findPendingTurnAnchor(OPERATION_REF))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingTurnAnchorState.COMMITTED));

    assertThat(rehearsal.run(scenario.anchor(), capability))
        .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.NOT_STARTED);
    assertThat(capability.invocations()).isOne();
  }

  @Test
  @Tag("failure")
  void uncertainFakeCapabilityRecordsUnknownAndNeverReplays() {
    Scenario scenario = approvedScenario(OPERATION_REF, OPERATION_REF);
    CountingFakeCapability capability = CountingFakeCapability.uncertain();
    TestOnlyRecoveryRehearsal rehearsal = scenario.rehearsal();

    assertThat(rehearsal.run(scenario.anchor(), capability))
        .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.UNKNOWN);
    assertThat(capability.invocations()).isOne();
    assertThat(scenario.operationStore().find(PendingOperationReference.of(OPERATION_REF)))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.UNKNOWN));
    assertThat(scenario.operationStore().findLedger(PendingOperationReference.of(OPERATION_REF)))
        .hasValueSatisfying(
            value -> {
              assertThat(value.state()).isEqualTo(SideEffectExecutionState.UNKNOWN);
              assertThat(value.safeResult()).isEmpty();
            });
    assertThat(scenario.sessions().load("session-1").messages()).hasSize(2);

    assertThat(rehearsal.run(scenario.anchor(), capability))
        .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.NOT_STARTED);
    assertThat(capability.invocations()).isOne();
  }

  @Test
  @Tag("failure")
  void failedConversationCommitBecomesCommitUnreportedAndNeverReplaysTheFakeCapability() {
    Scenario scenario = approvedScenario(OPERATION_REF, OPERATION_REF);
    scenario.sessions().appendTurn("session-1", pendingTurn());
    CountingFakeCapability capability = CountingFakeCapability.success();
    TestOnlyRecoveryRehearsal rehearsal = scenario.rehearsal();

    assertThat(rehearsal.run(scenario.anchor(), capability))
        .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.COMMIT_UNREPORTED);
    assertThat(capability.invocations()).isOne();
    assertThat(scenario.operationStore().find(PendingOperationReference.of(OPERATION_REF)))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.COMMIT_UNREPORTED));
    assertThat(scenario.operationStore().findLedger(PendingOperationReference.of(OPERATION_REF)))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(SideEffectExecutionState.SUCCEEDED));
    assertThat(scenario.sessions().findPendingTurnAnchor(OPERATION_REF))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingTurnAnchorState.STALE_SESSION));

    assertThat(rehearsal.run(scenario.anchor(), capability))
        .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.NOT_STARTED);
    assertThat(capability.invocations()).isOne();
  }

  @Test
  void rejectsAMismatchedOperationAndAnchorBeforeReservationOrFakeInvocation() {
    Scenario scenario = approvedScenario(OPERATION_REF, OTHER_REF);
    CountingFakeCapability capability = CountingFakeCapability.success();

    assertThat(scenario.rehearsal().run(scenario.anchor(), capability))
        .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.NOT_STARTED);
    assertThat(capability.invocations()).isZero();
    assertThat(scenario.operationStore().find(PendingOperationReference.of(OPERATION_REF)))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.PENDING_APPROVAL));
    assertThat(scenario.operationStore().findLedger(PendingOperationReference.of(OPERATION_REF)))
        .isEmpty();
  }

  @Test
  @Tag("compat")
  void executesEveryVersionedTestOnlyRecoveryRehearsalFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/pending-operation-v1.json").toFile());
    assertThat(fixture.path("cases").size()).isEqualTo(54);
    for (JsonNode testCase : fixture.path("cases")) {
      String id = testCase.path("id").asText();
      if (id.startsWith("anchor-rehearsal-")) {
        verifyFixture(id);
      }
    }
  }

  private void verifyFixture(String id) {
    switch (id) {
      case "anchor-rehearsal-commits-once" -> {
        Scenario scenario = approvedScenario(OPERATION_REF, OPERATION_REF);
        CountingFakeCapability capability = CountingFakeCapability.success();
        assertThat(scenario.rehearsal().run(scenario.anchor(), capability))
            .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.COMMITTED);
        assertThat(capability.invocations()).isOne();
      }
      case "anchor-rehearsal-unknown-never-replays" -> {
        Scenario scenario = approvedScenario(OPERATION_REF, OPERATION_REF);
        CountingFakeCapability capability = CountingFakeCapability.uncertain();
        assertThat(scenario.rehearsal().run(scenario.anchor(), capability))
            .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.UNKNOWN);
        assertThat(scenario.rehearsal().run(scenario.anchor(), capability))
            .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.NOT_STARTED);
        assertThat(capability.invocations()).isOne();
      }
      case "anchor-rehearsal-commit-unreported-never-replays" -> {
        Scenario scenario = approvedScenario(OPERATION_REF, OPERATION_REF);
        scenario.sessions().appendTurn("session-1", pendingTurn());
        CountingFakeCapability capability = CountingFakeCapability.success();
        assertThat(scenario.rehearsal().run(scenario.anchor(), capability))
            .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.COMMIT_UNREPORTED);
        assertThat(scenario.rehearsal().run(scenario.anchor(), capability))
            .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.NOT_STARTED);
        assertThat(capability.invocations()).isOne();
      }
      case "anchor-rehearsal-rejects-mismatched-binding" -> {
        Scenario scenario = approvedScenario(OPERATION_REF, OTHER_REF);
        CountingFakeCapability capability = CountingFakeCapability.success();
        assertThat(scenario.rehearsal().run(scenario.anchor(), capability))
            .isEqualTo(TestOnlyRecoveryRehearsal.Outcome.NOT_STARTED);
        assertThat(capability.invocations()).isZero();
      }
      default -> throw new AssertionError("未知 Test-only Recovery Fixture Case: " + id);
    }
  }

  private Scenario approvedScenario(String operationReference, String anchorReference) {
    Path scenarioDirectory = scenarioDirectory();
    ApprovalInboxSchemaInitializer inboxSchema =
        new ApprovalInboxSchemaInitializer(scenarioDirectory.resolve("approval-inbox.db"), 5_000);
    inboxSchema.initialize();
    JdbcPendingOperationStore operationStore =
        new JdbcPendingOperationStore(
            inboxSchema, new AesGcmPendingOperationCapsuleCipher(provider()));
    PendingOperation operation = operation(operationReference);
    operationStore.create(operation, inbox(operation), capsule(operation));
    new JdbcApprovalInbox(inboxSchema)
        .resolve(
            inbox(operation).reference(),
            ApprovalInboxDecision.APPROVED,
            "test-only-operator",
            ISSUED.plusSeconds(1));

    SqliteSchemaInitializer sessionSchema =
        new SqliteSchemaInitializer(scenarioDirectory.resolve("sessions.db"), 5_000);
    sessionSchema.initialize();
    JdbcSessionRepository sessions = new JdbcSessionRepository(sessionSchema);
    PendingTurnAnchor anchor =
        PendingTurnAnchor.pending(anchorReference, "session-1", 0, "pending-projection-v1");
    assertThat(sessions.appendPendingTurnIfNextSequence(pendingTurn(), anchor)).isTrue();
    return new Scenario(operationStore, sessions, anchor);
  }

  private static PendingOperation operation(String reference) {
    String arguments = "{\"value\":1}";
    ApprovalRequest request =
        new ApprovalRequest(
            "approval-id",
            ApprovalFingerprint.sessionBinding("session-1"),
            "turn-id",
            "call-id",
            "test_only_safe_capability",
            "v1",
            ToolRisk.WRITE,
            ApprovalFingerprint.argumentsHashJson(arguments),
            "idempotency-key",
            "测试专用安全摘要",
            ISSUED,
            ISSUED.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64));
    return PendingOperation.pending(PendingOperationReference.of(reference), request, 2, ISSUED);
  }

  private static ApprovalInboxEntry inbox(PendingOperation operation) {
    return ApprovalInboxEntry.pending(
        ApprovalInboxReference.of("AgICAgICAgICAgICAgICAg"), operation.approval());
  }

  private static PendingOperationCapsule capsule(PendingOperation operation) {
    return PendingOperationCapsule.forOperation(
        operation, "session-1", "{\"value\":1}", "test-only-boundary-v1");
  }

  private static PersistedTurn pendingTurn() {
    return new PersistedTurn(
        new ChatMessage(MessageRole.USER, "请求受控测试操作"),
        TURN_AT,
        new ChatMessage(MessageRole.ASSISTANT, "该操作等待审批。"),
        TURN_AT.plusSeconds(1));
  }

  private static PendingOperationKeyProvider provider() {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) 1);
    PendingOperationKey key =
        new PendingOperationKey("test-only-key-v1", new SecretKeySpec(bytes, "AES"));
    return new PendingOperationKeyProvider() {
      @Override
      public PendingOperationKey current() {
        return key;
      }

      @Override
      public Optional<PendingOperationKey> findByKeyId(String keyId) {
        return key.keyId().equals(keyId) ? Optional.of(key) : Optional.empty();
      }
    };
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private Path scenarioDirectory() {
    return tempDir.resolve("scenario-" + scenarioIndex++);
  }

  /**
   * Test-only composition of the existing durable boundaries. It is intentionally nested in test
   * sources so no production class, Bean, worker, route, or Tool registry can construct it.
   */
  private static final class TestOnlyRecoveryRehearsal {
    private final JdbcPendingOperationStore operationStore;
    private final JdbcSessionRepository sessions;
    private final Instant observedAt;

    private TestOnlyRecoveryRehearsal(
        JdbcPendingOperationStore operationStore,
        JdbcSessionRepository sessions,
        Instant observedAt) {
      this.operationStore = operationStore;
      this.sessions = sessions;
      this.observedAt = observedAt;
    }

    Outcome run(PendingTurnAnchor anchor, CountingFakeCapability capability) {
      if (anchor.state() != PendingTurnAnchorState.PENDING_APPROVAL
          || sessions
              .findPendingTurnAnchor(anchor.operationReference())
              .filter(anchor::equals)
              .isEmpty()) {
        return Outcome.NOT_STARTED;
      }
      PendingOperation operation =
          operationStore
              .find(PendingOperationReference.of(anchor.operationReference()))
              .orElse(null);
      if (operation == null || operation.expectedNextSequence() != anchor.resumeNextSequence()) {
        return Outcome.NOT_STARTED;
      }
      if (!operationStore.reserveApproved(operation.reference(), observedAt).acquired()) {
        return Outcome.NOT_STARTED;
      }
      operationStore.markRunning(operation.reference(), observedAt.plusSeconds(1));

      ToolResult safeResult;
      try {
        safeResult = capability.invoke();
      } catch (TestOnlyUncertainInvocation exception) {
        operationStore.markUnknown(
            operation.reference(), "TEST_ONLY_INVOKER_UNCERTAIN", observedAt.plusSeconds(2));
        return Outcome.UNKNOWN;
      }
      if (safeResult.status() != io.namei.agent.kernel.tool.ToolResultStatus.SUCCESS) {
        operationStore.markUnknown(
            operation.reference(), "TEST_ONLY_INVALID_SAFE_RESULT", observedAt.plusSeconds(2));
        return Outcome.UNKNOWN;
      }
      operationStore.markSucceeded(operation.reference(), safeResult, observedAt.plusSeconds(2));

      try {
        boolean committed =
            sessions.appendPendingResolutionIfAnchorMatches(
                anchor,
                new io.namei.agent.kernel.model.PendingTurnResolution(
                    anchor.projectionVersion(),
                    new ChatMessage(MessageRole.ASSISTANT, "测试专用安全完成投影。"),
                    TURN_AT.plusSeconds(2)));
        if (committed) {
          return Outcome.COMMITTED;
        }
      } catch (RuntimeException ignored) {
        // The side effect is already durably known; a conversation commit fault cannot retry it.
      }
      operationStore.markCommitUnreported(operation.reference(), observedAt.plusSeconds(3));
      return Outcome.COMMIT_UNREPORTED;
    }

    private enum Outcome {
      COMMITTED,
      UNKNOWN,
      COMMIT_UNREPORTED,
      NOT_STARTED
    }
  }

  private static final class CountingFakeCapability {
    private final boolean uncertain;
    private int invocations;

    private CountingFakeCapability(boolean uncertain) {
      this.uncertain = uncertain;
    }

    static CountingFakeCapability success() {
      return new CountingFakeCapability(false);
    }

    static CountingFakeCapability uncertain() {
      return new CountingFakeCapability(true);
    }

    ToolResult invoke() {
      invocations++;
      if (uncertain) {
        throw new TestOnlyUncertainInvocation();
      }
      return ToolResult.success("测试专用安全结果。");
    }

    int invocations() {
      return invocations;
    }
  }

  private static final class TestOnlyUncertainInvocation extends RuntimeException {}

  private record Scenario(
      JdbcPendingOperationStore operationStore,
      JdbcSessionRepository sessions,
      PendingTurnAnchor anchor) {
    TestOnlyRecoveryRehearsal rehearsal() {
      return new TestOnlyRecoveryRehearsal(operationStore, sessions, ISSUED.plusSeconds(2));
    }
  }
}
