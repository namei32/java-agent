package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.control.ControlStableCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Connects every versioned pending-recovery case to its production boundary or focused test. */
@Tag("compat")
class PendingRecoveryControlGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, FocusedOwner> FOCUSED_OWNERS =
      Map.ofEntries(
          owner(
              "pending-recovery-default-unmapped",
              "config/MemoryForgetCapabilityConfigurationTest",
              "defaultDisabledCreatesNoKeyStoreCapabilityOrDatabase"),
          owner(
              "loopback-control-alone-does-not-map-pending-recovery",
              "config/MemoryForgetCapabilityConfigurationTest",
              "defaultDisabledCreatesNoKeyStoreCapabilityOrDatabase"),
          owner(
              "frozen-contract-does-not-create-worker-or-capability",
              "config/MemoryForgetCapabilityConfigurationTest",
              "defaultDisabledCreatesNoKeyStoreCapabilityOrDatabase"),
          owner(
              "future-enabled-surface-reuses-loopback-operator-authentication",
              "config/MemoryForgetCapabilityConfigurationTest",
              "exactExplicitPrerequisitesWireTheNarrowCapabilityWithoutRegisteringATool"),
          owner(
              "status-uses-get-with-opaque-path-reference-only",
              "control/LoopbackRequestGuardTest",
              "acceptsOnlyLoopbackRemoteApprovedHostAndExactOptionalOrigin"),
          owner(
              "resume-uses-post-with-opaque-path-reference-only",
              "control/LoopbackRequestGuardTest",
              "acceptsOnlyLoopbackRemoteApprovedHostAndExactOptionalOrigin"),
          owner(
              "cancel-uses-post-with-opaque-path-reference-only",
              "control/LoopbackRequestGuardTest",
              "acceptsOnlyLoopbackRemoteApprovedHostAndExactOptionalOrigin"),
          owner(
              "lowercase-action-is-rejected",
              "control/LoopbackRequestGuardTest",
              "rejectsCrossOriginUnknownShapeQueryAndEveryNonEmptyBody"),
          owner(
              "body-is-rejected-before-recovery-lookup",
              "control/LoopbackRequestGuardTest",
              "rejectsCrossOriginUnknownShapeQueryAndEveryNonEmptyBody"),
          owner(
              "query-is-rejected-before-recovery-lookup",
              "control/LoopbackRequestGuardTest",
              "rejectsCrossOriginUnknownShapeQueryAndEveryNonEmptyBody"),
          applicationOwner(
              "resume-requires-approved-pending-anchor-and-capability",
              "MemoryForgetRecoveryCoordinatorTest",
              "usesTheStaticBoundCapabilityOnlyAfterReservationThenCommitsASafeProjection"),
          sqliteOwner(
              "resume-pending-approval-is-not-an-execution-right",
              "PendingOperationReservationTest",
              "leavesAPendingApprovalAndOperationUntouchedUntilAnOperatorApprovesIt"),
          applicationOwner(
              "resume-cancelled-is-not-an-execution-right",
              "MemoryForgetControlServiceTest",
              "cancelsOnlyTheUnconsumedOperationThenItsMatchingAnchor"),
          applicationOwner(
              "resume-unknown-never-retries",
              "MemoryForgetControlServiceTest",
              "onlyDelegatesResumeForNonterminalOperationsAndMapsUnknownWithoutReplay"),
          applicationOwner(
              "resume-commit-unreported-never-replays",
              "MemoryForgetRecoveryCoordinatorTest",
              "makesConversationCommitFailureTerminalWithoutReplayingForget"),
          owner(
              "resume-missing-reference-is-not-found",
              "control/PendingOperationControllerTest",
              "projectsOnlySafeStatusAndMapsResumeCancelAndErrorsToStableResponses"),
          applicationOwner(
              "cancel-pending-is-idempotently-cancelled",
              "MemoryForgetControlServiceTest",
              "cancelsOnlyTheUnconsumedOperationThenItsMatchingAnchor"),
          applicationOwner(
              "cancel-approved-is-idempotently-cancelled-before-running",
              "MemoryForgetControlServiceTest",
              "cancelsOnlyTheUnconsumedOperationThenItsMatchingAnchor"),
          owner(
              "cancel-running-is-not-a-retry-or-kill-signal",
              "control/PendingOperationControllerTest",
              "projectsOnlySafeStatusAndMapsResumeCancelAndErrorsToStableResponses"),
          owner(
              "cancel-terminal-is-idempotent-without-invocation",
              "control/PendingOperationControllerTest",
              "returnsTheCurrentSafeStatusForAnAlreadyTerminalCancel"),
          applicationOwner(
              "status-projects-only-stable-lifecycle-fields",
              "MemoryForgetControlServiceTest",
              "statusProjectsOnlyTheVersionStateAndUpdateTime"),
          owner(
              "status-unknown-is-stable-without-error-body",
              "control/PendingOperationControllerTest",
              "projectsOnlySafeStatusAndMapsResumeCancelAndErrorsToStableResponses"),
          applicationOwner(
              "status-commit-unreported-is-stable-without-safe-result",
              "MemoryForgetRecoveryCoordinatorTest",
              "makesConversationCommitFailureTerminalWithoutReplayingForget"),
          owner(
              "status-projection-never-leaks-sensitive-boundings",
              "control/PendingOperationControllerTest",
              "projectsOnlySafeStatusAndMapsResumeCancelAndErrorsToStableResponses"));

  @TestFactory
  Stream<DynamicTest> everyVersionedCaseUsesAProductionBoundaryOrFocusedOwner() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("control-plane/pending-recovery-control-v1.json"));
    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("suite").asString()).isEqualTo("pending-recovery-control-v1");
    assertThat(fixture.path("cases")).hasSize(24);
    assertThat(FOCUSED_OWNERS).hasSize(24);

    var identifiers = new HashSet<String>();
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode testCase : fixture.path("cases")) {
      String identifier = testCase.path("id").asString();
      assertThat(identifiers.add(identifier)).as("重复 Case ID: %s", identifier).isTrue();
      tests.add(
          DynamicTest.dynamicTest(identifier, () -> verify(testCase, fixture.path("defaults"))));
    }
    return tests.stream();
  }

  private static void verify(JsonNode testCase, JsonNode defaults) throws Exception {
    JsonNode expected = testCase.path("expected");
    if (expected.has("code")) {
      ControlStableCode code = ControlStableCode.parse(expected.path("code").asString());
      assertThat(code.retryable()).isFalse();
    }
    if (expected.has("fields")) {
      assertThat(expected.path("fields").valueStream().map(JsonNode::asString))
          .containsExactly("schemaVersion", "state", "updatedAt");
    }
    if (expected.has("leaks") && !expected.path("leaks").asBoolean()) {
      String serialized = JSON.writeValueAsString(expected);
      assertThat(serialized)
          .doesNotContain(
              defaults.path("rawSession").asString(),
              defaults.path("rawTool").asString(),
              defaults.path("rawArguments").asString(),
              defaults.path("rawResult").asString(),
              defaults.path("rawApproval").asString());
    }
    assertFocusedOwner(FOCUSED_OWNERS.get(testCase.path("id").asString()));
  }

  private static Map.Entry<String, FocusedOwner> owner(String caseId, String test, String method) {
    return Map.entry(
        caseId,
        new FocusedOwner(
            Path.of("agent-bootstrap/src/test/java/io/namei/agent/bootstrap/" + test + ".java"),
            method));
  }

  private static Map.Entry<String, FocusedOwner> applicationOwner(
      String caseId, String testClass, String method) {
    return Map.entry(
        caseId,
        new FocusedOwner(
            Path.of(
                "agent-application/src/test/java/io/namei/agent/application/"
                    + testClass
                    + ".java"),
            method));
  }

  private static Map.Entry<String, FocusedOwner> sqliteOwner(
      String caseId, String testClass, String method) {
    return Map.entry(
        caseId,
        new FocusedOwner(
            Path.of(
                "adapter-sqlite/src/test/java/io/namei/agent/adapter/sqlite/"
                    + testClass
                    + ".java"),
            method));
  }

  private static void assertFocusedOwner(FocusedOwner owner) throws Exception {
    assertThat(owner).as("Fixture Case 没有生产测试归属").isNotNull();
    Path source = repositoryRoot().resolve(owner.source()).normalize();
    assertThat(source).isRegularFile();
    assertThat(Files.readString(source))
        .containsPattern("\\bvoid\\s+" + Pattern.quote(owner.method()) + "\\s*\\(");
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private static Path repositoryRoot() {
    return goldenRoot().getParent().getParent();
  }

  private record FocusedOwner(Path source, String method) {}
}
