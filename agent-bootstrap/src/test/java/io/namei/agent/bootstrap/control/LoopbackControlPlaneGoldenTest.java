package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.application.control.ControlCancellationOutcome;
import io.namei.agent.bootstrap.channel.ChannelHost;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlEventProjection;
import io.namei.agent.kernel.control.ControlStableCode;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class LoopbackControlPlaneGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
  private static final Map<String, FocusedOwner> FOCUSED_OWNERS =
      Map.ofEntries(
          owner(
              "wildcard-binding-rejected",
              "ControlPlaneLoopbackBindingTest",
              "rejectsWildcardRemoteHostnameAndUnprovableBindingsBeforeRuntimeCreation"),
          owner(
              "session-created-with-bearer",
              "ControlPlaneSecurityFilterTest",
              "createsAndRevokesOneTimeHttpSessionWithExactSafeEnvelope"),
          owner(
              "missing-bearer-uses-uniform-error",
              "ControlPlaneSecurityFilterTest",
              "everyInvalidCredentialReturnsOneUniformSafeUnauthorizedResponse"),
          owner(
              "expired-bearer-uses-uniform-error",
              "ControlPlaneSecurityFilterTest",
              "everyInvalidCredentialReturnsOneUniformSafeUnauthorizedResponse"),
          owner(
              "revoked-bearer-uses-uniform-error",
              "ControlPlaneSecurityFilterTest",
              "everyInvalidCredentialReturnsOneUniformSafeUnauthorizedResponse"),
          owner(
              "session-capacity-fails-closed",
              "ControlPlaneSecurityFilterTest",
              "sessionCapacityReturnsTheStableTooManyRequestsEnvelope"),
          owner(
              "status-channels-sorted",
              "ControlPlaneStatusServiceTest",
              "exposesExactSafeStatusAndStableSortedActiveTurns"),
          owner(
              "status-snapshot-failure-degrades",
              "ControlPlaneStatusServiceTest",
              "isolatesOneChannelSnapshotFailureAndDegradesWithStableControlCode"),
          owner(
              "unknown-execution-is-count-only",
              "ControlPlaneStatusServiceTest",
              "keepsUnknownExecutionAsReliabilityCountWithoutCreatingAnActiveTurn"),
          owner(
              "active-turn-safe-projection",
              "ControlPlaneStatusServiceTest",
              "exposesExactSafeStatusAndStableSortedActiveTurns"),
          owner(
              "active-turn-omits-sensitive-identifiers",
              "ControlPlaneStatusServiceTest",
              "exposesExactSafeStatusAndStableSortedActiveTurns"),
          owner(
              "registry-saturation-degrades-only",
              "ControlPlaneStatusServiceTest",
              "reportsRegistrySaturationWithoutCancellingOrRejectingTheRunningTurn"),
          owner(
              "active-turns-use-stable-order",
              "ControlPlaneStatusServiceTest",
              "exposesExactSafeStatusAndStableSortedActiveTurns"),
          owner(
              "cancel-concurrent-call-has-one-winner",
              "ControlPlaneConcurrencyFailureIT",
              "twoDashboardRequestsHaveExactlyOneFirstWriter"),
          owner(
              "cancel-never-mutates-ledger",
              "ControlPlaneCancellationIT",
              "targetCancellationUsesExistingSourceAndNeverWritesReliableLedger"),
          owner(
              "stream-rejects-last-event-id",
              "ControlPlaneSseControllerTest",
              "rejectsReplayBeforeCommittingTheEventStream"),
          owner(
              "stream-slow-consumer-is-isolated",
              "ControlPlaneSseFailureIT",
              "slowConsumerKeepsCapacityReservedAndAuditsItsStableCloseCode"),
          owner(
              "stream-disconnect-cleans-subscription",
              "ControlPlaneSseFailureIT",
              "writerFailureReleasesOnlyItsSubscriptionAndNeverCancelsTurn"),
          applicationOwner(
              "stream-lifetime-closes-without-cancel",
              "ControlEventHubTest",
              "actorRevocationLifetimeAndShutdownReleaseWithoutTurnCancellation"),
          owner(
              "shutdown-clears-sessions-and-streams",
              "ControlPlaneShutdownIT",
              "bothSpringDestroyOrdersClearSessionsStreamsAndRegistryWithoutCancellingTurn"),
          applicationOwner(
              "observer-failure-never-fails-primary",
              "ObservedOutboundMessageSinkTest",
              "observerRuntimeFailureCannotChangePrimarySuccess"),
          owner(
              "audit-failure-never-changes-result",
              "ControlPlaneAuditTest",
              "hashesSensitiveReferencesAndNeverLetsSinkFailureEscape"),
          owner(
              "disabled-mode-has-zero-control-resources",
              "ControlPlaneDisabledBootstrapTest",
              "defaultDisabledCreatesNoRuntimeRegistrySubscriberRandomValueOrFile"));

  @TestFactory
  Stream<DynamicTest> everyVersionedCaseUsesProductionBoundaryOrDeclaresFocusedOwner()
      throws Exception {
    JsonNode fixture = fixture();
    var identifiers = new HashSet<String>();
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode testCase : fixture.path("cases")) {
      String identifier = testCase.path("id").asString();
      assertThat(identifiers.add(identifier)).as("重复 Case ID: %s", identifier).isTrue();
      tests.add(
          DynamicTest.dynamicTest(identifier, () -> verify(testCase, fixture.path("defaults"))));
    }
    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(tests).hasSize(48);
    return tests.stream();
  }

  private static void verify(JsonNode testCase, JsonNode defaults) throws Exception {
    JsonNode expected = testCase.path("expected");
    if (expected.has("code") && expected.path("code").asString().startsWith("CONTROL_")) {
      ControlStableCode code = ControlStableCode.parse(expected.path("code").asString());
      assertThat(code.retryable())
          .isEqualTo(expected.path("retryable").asBoolean(code.retryable()));
    }
    if (expected.has("result")) {
      assertThat(ControlCancelResult.valueOf(expected.path("result").asString()).name())
          .isEqualTo(expected.path("result").asString());
    }
    FocusedOwner focusedOwner = FOCUSED_OWNERS.get(testCase.path("id").asString());
    if (focusedOwner != null) {
      assertFocusedOwner(focusedOwner);
      return;
    }
    switch (testCase.path("group").asString()) {
      case "mode-security" -> verifyModeSecurity(testCase);
      case "session-auth" -> verifySession(testCase);
      case "status-turn" -> verifyStatusTurn(testCase);
      case "cancellation" -> verifyCancellation(testCase);
      case "sse" -> verifySse(testCase, defaults);
      case "shutdown-failure" -> verifyShutdownFailure(testCase);
      default -> throw new AssertionError("未知 Fixture Group");
    }
  }

  private static void verifyModeSecurity(JsonNode testCase) {
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    String component = testCase.path("component").asString();
    if ("mode".equals(component)) {
      String configured =
          input.path("configured").isNull() ? "DISABLED" : input.path("configured").asString();
      assertThat(ControlPlaneMode.parse(configured).name())
          .isEqualTo(expected.path("mode").asString());
      return;
    }
    if ("binding".equals(component)) {
      assertThat(input.path("serverAddress").asString()).isNotIn("127.0.0.1", "::1");
      assertThat(expected.path("code").asString()).isEqualTo("CONTROL_BINDING_INVALID");
      return;
    }
    var request =
        new MockHttpServletRequest(input.path("method").asString("GET"), "/api/v1/control/status");
    request.setRemoteAddr(input.path("remoteAddress").asString("127.0.0.1"));
    request.setScheme("http");
    request.addHeader("Host", input.path("host").asString("127.0.0.1"));
    if (input.has("origin")) {
      request.addHeader("Origin", input.path("origin").asString());
    }
    if (input.has("xForwardedFor")) {
      request.addHeader("X-Forwarded-For", input.path("xForwardedFor").asString());
    }
    assertThatThrownBy(() -> new LoopbackRequestGuard().validate(request))
        .isInstanceOfSatisfying(
            ControlRequestRejectedException.class,
            failure ->
                assertThat(failure.code().name()).isEqualTo(expected.path("code").asString()));
  }

  private static void verifySession(JsonNode testCase) {
    var clock = new OperatorSessionStoreTest.MutableClock(NOW);
    var store =
        new OperatorSessionStore(
            clock,
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(1),
            1,
            actor -> {});
    OperatorSessionCreated created = store.create();
    String identifier = testCase.path("id").asString();
    if ("session-capacity-fails-closed".equals(identifier)) {
      assertThatThrownBy(store::create).isInstanceOf(OperatorSessionCapacityException.class);
    } else if ("expired-bearer-uses-uniform-error".equals(identifier)) {
      clock.advance(Duration.ofMinutes(1));
      assertThat(store.authenticate(created.accessToken())).isEmpty();
    } else if ("revoked-bearer-uses-uniform-error".equals(identifier)) {
      String actor = store.authenticate(created.accessToken()).orElseThrow().actorRef();
      store.revoke(actor);
      assertThat(store.authenticate(created.accessToken())).isEmpty();
    } else if ("missing-bearer-uses-uniform-error".equals(identifier)) {
      assertThat(store.authenticate(null)).isEmpty();
    } else {
      assertThat(store.authenticate(created.accessToken())).isPresent();
      assertThat(store.toString()).doesNotContain(created.accessToken());
    }
  }

  private static void verifyStatusTurn(JsonNode testCase) {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var source = new TurnCancellationSource();
    var registration = runtime.register("telegram", ControlCancellationHandle.from(source), NOW);
    if ("terminal-tombstone-not-listed".equals(testCase.path("id").asString())) {
      var route = new MessageRoute("telegram", "fixture");
      registration.observe(OutboundMessage.started("turn", "session", route));
      registration.observe(OutboundMessage.completed("turn", "session", route, 1, "完成"));
    }
    var host = new ChannelHost(List.of());
    host.start();
    var service =
        new ControlPlaneStatusService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            host,
            runtime,
            ControlPlaneStatusServiceTest.properties());
    assertThat(service.status().schemaVersion()).isEqualTo(1);
    assertThat(service.turns().items())
        .hasSize("terminal-tombstone-not-listed".equals(testCase.path("id").asString()) ? 0 : 1)
        .allSatisfy(item -> assertThat(item.toString()).contains("turnRef=<redacted>"));
  }

  private static void verifyCancellation(JsonNode testCase) throws Exception {
    String identifier = testCase.path("id").asString();
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var source = new TurnCancellationSource();
    String existing = testCase.path("input").path("existingReason").asString();
    if (!existing.isEmpty()) {
      source.cancel(TurnCancellationCode.valueOf(existing));
    }
    var registration = runtime.register("telegram", ControlCancellationHandle.from(source), NOW);
    ControlTurnRef reference = registration.turnRef().orElseThrow();
    if ("cancel-terminal-returns-stable-result".equals(identifier)) {
      var route = new MessageRoute("telegram", "fixture");
      registration.observe(OutboundMessage.started("turn", "session", route));
      registration.observe(OutboundMessage.completed("turn", "session", route, 1, "完成"));
    }
    if ("cancel-expired-reference-is-not-found".equals(identifier)) {
      reference = ControlPlaneStatusServiceTest.reference(99);
    }
    if ("cancel-target-is-isolated".equals(identifier)) {
      var otherSource = new TurnCancellationSource();
      runtime.register("telegram", ControlCancellationHandle.from(otherSource), NOW.plusSeconds(1));
      assertThat(runtime.registry().cancel(reference).result())
          .isEqualTo(ControlCancelResult.CANCELLATION_REQUESTED);
      assertThat(source.token().isCancellationRequested()).isTrue();
      assertThat(otherSource.token().isCancellationRequested()).isFalse();
      return;
    }
    ControlCancellationOutcome outcome = runtime.registry().cancel(reference);
    assertThat(outcome.result().name())
        .isEqualTo(testCase.path("expected").path("result").asString());
    if (testCase.path("expected").has("state")) {
      assertThat(outcome.state().orElseThrow().name())
          .isEqualTo(testCase.path("expected").path("state").asString());
    }
  }

  private static void verifySse(JsonNode testCase, JsonNode defaults) throws Exception {
    JsonNode input = testCase.path("input");
    if ("event-projection".equals(testCase.path("component").asString())) {
      ControlTurnRef reference = ControlTurnRef.parse(defaults.path("turnRef").asString());
      OutboundMessage message =
          new OutboundMessage(
              1,
              "raw-turn-secret",
              "telegram:raw-session-secret",
              new MessageRoute("telegram", "raw-route-secret"),
              input.path("sequence").asLong(),
              io.namei.agent.kernel.channel.OutboundMessageType.valueOf(
                  input.path("type").asString()),
              input.path("content").asString(),
              input.path("code").asString(),
              input.path("retryable").asBoolean());
      ControlEventProjection projection = ControlEventProjection.from(reference, message);
      JsonNode expected = testCase.path("expected");
      if (expected.has("sequence")) {
        assertThat(projection.sequence()).isEqualTo(expected.path("sequence").asLong());
        assertThat(projection.type().name()).isEqualTo(expected.path("type").asString());
        assertThat(projection.content()).isEqualTo(expected.path("content").asString());
        assertThat(projection.code()).isEqualTo(expected.path("code").asString());
        assertThat(projection.retryable()).isEqualTo(expected.path("retryable").asBoolean());
      }
      assertThat(projection.toString()).doesNotContain("raw-turn", "raw-session", "raw-route");
      return;
    }
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var registration =
        runtime.register(
            "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), NOW);
    if ("stream-opened-is-future-only".equals(testCase.path("id").asString())) {
      var route = new MessageRoute("telegram", "fixture");
      registration.observe(OutboundMessage.started("turn", "session", route));
      for (int sequence = 1; sequence <= 3; sequence++) {
        registration.observe(
            OutboundMessage.delta("turn", "session", route, sequence, "delta-" + sequence));
      }
    }
    var subscription =
        runtime.eventHub().subscribe(registration.turnRef().orElseThrow(), "fixture-actor");
    assertThat(subscription.opening().replaySupported()).isFalse();
    if ("stream-opened-is-future-only".equals(testCase.path("id").asString())) {
      assertThat(subscription.opening().lastSequence())
          .isEqualTo(testCase.path("expected").path("lastSequence").asLong());
      assertThat(subscription.poll(Duration.ZERO)).isEmpty();
    }
    subscription.close();
  }

  private static void verifyShutdownFailure(JsonNode testCase) {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var audit =
        new ControlPlaneAudit(
            Clock.fixed(NOW, ZoneOffset.UTC),
            event -> {
              if (testCase.path("id").asString().contains("audit-failure")) {
                throw new IllegalStateException("audit-secret");
              }
            });
    audit.record("FIXTURE", "ACCEPTED", null, "request", null, null, 0, 0);
    runtime.close();
    assertThat(runtime.registry().snapshot().activeTurns()).isEmpty();
  }

  private static JsonNode fixture() throws Exception {
    return JSON.readTree(goldenRoot().resolve("control-plane/loopback-control-plane-v1.json"));
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private static Map.Entry<String, FocusedOwner> owner(
      String caseId, String testClass, String method) {
    return owner(
        caseId,
        "agent-bootstrap/src/test/java/io/namei/agent/bootstrap/control/",
        testClass,
        method);
  }

  private static Map.Entry<String, FocusedOwner> applicationOwner(
      String caseId, String testClass, String method) {
    return owner(
        caseId,
        "agent-application/src/test/java/io/namei/agent/application/control/",
        testClass,
        method);
  }

  private static Map.Entry<String, FocusedOwner> owner(
      String caseId, String sourceDirectory, String testClass, String method) {
    return Map.entry(
        caseId, new FocusedOwner(Path.of(sourceDirectory + testClass + ".java"), method));
  }

  private static void assertFocusedOwner(FocusedOwner owner) throws Exception {
    Path source = repositoryRoot().resolve(owner.source()).normalize();
    assertThat(source).isRegularFile();
    assertThat(Files.readString(source))
        .containsPattern("\\bvoid\\s+" + Pattern.quote(owner.method()) + "\\s*\\(");
  }

  private static Path repositoryRoot() {
    return goldenRoot().getParent().getParent();
  }

  private record FocusedOwner(Path source, String method) {}
}
