package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.proactive.DriftResult;
import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ReadOnlyProactiveDecisionContractFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

  @Test
  void consumesEveryR14P1ReadOnlyDecisionContractCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("proactive/r14-read-only-proactive-decision-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("proactive/r14-read-only-proactive-decision-v1");
    assertThat(fixture.path("source").asString()).isEqualTo("java-contract");
    assertThat(fixture.path("cases")).hasSize(15);

    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase, fixture.path("defaults"));
    }
  }

  private static void verify(JsonNode testCase, JsonNode defaults) {
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    var cancellation = new TurnCancellationSource();
    if ("BEFORE".equals(input.path("cancel").asString())) {
      cancellation.cancel();
    }
    var sourceCalls = new AtomicInteger();
    var driftCalls = new AtomicInteger();
    var audit = new ArrayList<ProactiveAuditEvent>();
    var runner =
        new ReadOnlyProactiveDecisionRunner(
            gate(input),
            source(input, defaults, cancellation, sourceCalls),
            new ReadOnlyDriftRunner(drift(input, defaults, driftCalls)),
            audit::add,
            Clock.fixed(NOW, ZoneOffset.UTC));

    ReadOnlyProactiveDecision projection = runner.evaluate(lease(input), cancellation.token());

    assertThat(projection.kind().name())
        .as(testCase.path("id").asString())
        .isEqualTo(expected.path("kind").asString());
    if (expected.has("code")) {
      assertThat(projection.code().orElseThrow().name())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("code").asString());
    } else {
      assertThat(projection.code()).as(testCase.path("id").asString()).isEmpty();
    }
    if (expected.has("sourceCalls")) {
      assertThat(sourceCalls.get())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("sourceCalls").asInt());
    }
    if (expected.has("driftCalls")) {
      assertThat(driftCalls.get())
          .as(testCase.path("id").asString())
          .isEqualTo(expected.path("driftCalls").asInt());
    }
    if (expected.has("externalDelivery")) {
      assertThat(projection.deliveryBoundary().allowsExternalDelivery())
          .isEqualTo(expected.path("externalDelivery").asBoolean());
    }
    if (expected.has("transportAuthorized")) {
      assertThat(projection.deliveryBoundary().transportAuthorized())
          .isEqualTo(expected.path("transportAuthorized").asBoolean());
    }
    if (expected.has("memoryMutations")) {
      assertThat(projection.memoryMutationCount())
          .isEqualTo(expected.path("memoryMutations").asInt());
    }
    if (expected.has("auditEvents")) {
      assertThat(audit).hasSize(expected.path("auditEvents").asInt());
    }
    if (expected.has("actions")) {
      assertThat(audit.stream().map(event -> event.action().name()).toList())
          .containsExactlyElementsOf(
              StreamSupport.stream(expected.path("actions").spliterator(), false)
                  .map(JsonNode::asString)
                  .toList());
    }
    String rendered = projection + audit.toString();
    if (expected.path("leaksSourceText").asBoolean()) {
      assertThat(rendered).contains(defaults.path("sourceText").asString());
    } else if (expected.has("leaksSourceText")) {
      assertThat(rendered).doesNotContain(defaults.path("sourceText").asString());
    }
    if (expected.path("leaksDriftText").asBoolean()) {
      assertThat(rendered).contains(defaults.path("driftText").asString());
    } else if (expected.has("leaksDriftText")) {
      assertThat(rendered).doesNotContain(defaults.path("driftText").asString());
    }
  }

  private static ProactiveGate gate(JsonNode input) {
    String gate = input.path("gate").asString();
    return ignored ->
        "REQUESTED".equals(gate)
            ? ProactiveDecision.requested()
            : ProactiveDecision.skipped(ProactiveStableCode.parse(gate));
  }

  private static FixedLocalProactiveSource source(
      JsonNode input, JsonNode defaults, TurnCancellationSource cancellation, AtomicInteger calls) {
    return ignored -> {
      calls.incrementAndGet();
      return switch (input.path("source").asString()) {
        case "EMPTY" -> Optional.empty();
        case "THROW" -> throw new IllegalStateException("local fake source failure");
        case "CANCEL" -> {
          cancellation.cancel();
          yield Optional.empty();
        }
        case "ITEM" ->
            Optional.of(
                io.namei.agent.kernel.proactive.ProactiveSourceItem.fixedLocal(
                    io.namei.agent.kernel.proactive.ProactiveSourceKind.FIXED_LOCAL,
                    "fixture-alert",
                    defaults.path("sourceText").asString()));
        default -> throw new AssertionError("未知 Source Fixture");
      };
    };
  }

  private static ReadOnlyDriftProbe drift(JsonNode input, JsonNode defaults, AtomicInteger calls) {
    return ignored -> {
      calls.incrementAndGet();
      return switch (input.path("drift").asString()) {
        case "CLEAN" -> DriftResult.clean();
        case "DETECTED" -> DriftResult.detected(defaults.path("driftText").asString());
        case "CANCELLED" -> DriftResult.cancelled();
        case "THROW" -> throw new IllegalStateException("local fake drift failure");
        default -> throw new AssertionError("未知 Drift Fixture");
      };
    };
  }

  private static ProactiveJobLease lease(JsonNode input) {
    return new ProactiveJobLease(
        new ScheduledJob(
            ProactiveJobRef.parse("daily-summary"),
            new ProactiveSchedule(ProactiveScheduleKind.AT, NOW, null),
            "a".repeat(64),
            "b".repeat(64),
            ProactiveJobState.RUNNING,
            1,
            3),
        "proactive-local",
        "EXPIRED".equals(input.path("lease").asString()) ? NOW : NOW.plusSeconds(30),
        2);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
