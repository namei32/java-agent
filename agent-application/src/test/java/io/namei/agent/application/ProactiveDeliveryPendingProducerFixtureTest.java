package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import io.namei.agent.kernel.proactive.ProactiveSourceKind;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ProactiveDeliveryPendingProducerFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void consumesEveryR14P2BFakeDeliveryPreparationCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(
            goldenRoot().resolve("proactive/r14-proactive-fake-delivery-preparation-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("proactive/r14-proactive-fake-delivery-preparation-v1");
    assertThat(fixture.path("cases")).hasSize(8);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase, fixture.path("defaults"));
    }
  }

  private static void verify(JsonNode testCase, JsonNode defaults) {
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    var scenario = new Scenario(defaults.path("recipientRef").asString());
    var cancellation = new TurnCancellationSource();
    if ("BEFORE".equals(input.path("cancel").asString())) {
      cancellation.cancel();
    }
    LocalProactiveCandidateResult candidate =
        "READY".equals(input.path("candidate").asString())
            ? candidate(
                defaults.path("sourceText").asString(),
                "EXPIRED".equals(input.path("lease").asString()))
            : LocalProactiveCandidateResult.skipped(ProactiveStableCode.PROACTIVE_NO_SOURCE);

    ProactiveDeliveryPreparationOutcome outcome;
    if (input.path("concurrent").asBoolean()) {
      List<ProactiveDeliveryPreparationOutcome> outcomes =
          concurrentPrepare(scenario, candidate, cancellation);
      assertThat(outcomes.stream().map(value -> value.kind().name()).toList())
          .containsExactlyInAnyOrderElementsOf(
              expected.path("outcomes").valueStream().map(JsonNode::asString).toList());
      outcome =
          outcomes.stream()
              .filter(value -> value.kind() == ProactiveDeliveryPreparationOutcome.Kind.PENDING)
              .findFirst()
              .orElseThrow();
    } else if ("FAIL_ONCE".equals(input.path("store").asString())) {
      scenario.store.failNextCreate = true;
      assertThatThrownBy(() -> scenario.producer.prepare(candidate, cancellation.token()))
          .isInstanceOf(ProactiveDeliveryPreparationException.class);
      outcome = scenario.producer.prepare(candidate, cancellation.token());
    } else {
      outcome = scenario.producer.prepare(candidate, cancellation.token());
    }
    if (input.path("repeat").asBoolean()) {
      ProactiveDeliveryPreparationOutcome first = outcome;
      outcome = scenario.producer.prepare(candidate, cancellation.token());
      assertThat(first.kind().name()).isEqualTo(expected.path("firstOutcome").asString());
    }

    assertThat(outcome.kind().name())
        .as(testCase.path("id").asString())
        .isEqualTo(expected.path("outcome").asString());
    assertThat(scenario.store.createAttempts).isEqualTo(expected.path("createAttempts").asInt());
    if (expected.has("state")) {
      assertThat(scenario.store.operation)
          .hasValueSatisfying(
              operation ->
                  assertThat(operation.state().name())
                      .isEqualTo(expected.path("state").asString()));
    }
    if (expected.has("anchor")) {
      assertThat(scenario.store.operation)
          .hasValueSatisfying(
              operation ->
                  assertThat(operation.anchor().state().name())
                      .isEqualTo(expected.path("anchor").asString()));
    }
    String rendered = outcome + scenario.store.toString();
    if (expected.has("leaksSourceText")) {
      assertThat(rendered).doesNotContain(defaults.path("sourceText").asString());
    }
    if (expected.has("leaksRecipientRef")) {
      assertThat(rendered).doesNotContain(defaults.path("recipientRef").asString());
    }
  }

  private static List<ProactiveDeliveryPreparationOutcome> concurrentPrepare(
      Scenario scenario,
      LocalProactiveCandidateResult candidate,
      TurnCancellationSource cancellation) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<ProactiveDeliveryPreparationOutcome>> tasks =
          List.of(
              () -> scenario.producer.prepare(candidate, cancellation.token()),
              () -> scenario.producer.prepare(candidate, cancellation.token()));
      return executor.invokeAll(tasks).stream()
          .map(
              future -> {
                try {
                  return future.get();
                } catch (Exception exception) {
                  throw new AssertionError(exception);
                }
              })
          .toList();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static LocalProactiveCandidateResult candidate(String sourceText, boolean expired) {
    return LocalProactiveCandidateResult.candidateReady(
        new LocalProactiveCandidate(
            new ProactiveJobLease(
                new ScheduledJob(
                    ProactiveJobRef.parse("daily-summary"),
                    new ProactiveSchedule(ProactiveScheduleKind.AT, NOW, null),
                    "a".repeat(64),
                    "b".repeat(64),
                    ProactiveJobState.RUNNING,
                    1,
                    3),
                "proactive-local",
                expired ? NOW : NOW.plusSeconds(30),
                2),
            ProactiveSourceItem.fixedLocal(
                ProactiveSourceKind.FIXED_LOCAL, "fixture-alert", sourceText),
            NOW));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private static final class Scenario {
    private final Store store = new Store();
    private final ProactiveDeliveryPendingProducer producer;

    private Scenario(String recipientRef) {
      producer =
          new ProactiveDeliveryPendingProducer(
              store,
              () -> ProactiveDeliveryOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
              () -> ApprovalInboxReference.of("BBBBBBBBBBBBBBBBBBBBBB"),
              new FixedIds(),
              new AesGcmProactiveDeliveryCapsuleCipher(
                  new SecretKeySpec(new byte[32], "AES"),
                  "fixture-key",
                  new SecureRandom(new byte[] {1})),
              FakeProactiveRecipientReference.of(recipientRef),
              CLOCK,
              Duration.ofMinutes(5));
    }
  }

  private static final class FixedIds implements IdGenerator {
    @Override
    public String newTurnId() {
      return "turn-fixture";
    }

    @Override
    public String newApprovalId() {
      return "approval-fixture";
    }

    @Override
    public String newIdempotencyKey() {
      return "idempotency-fixture";
    }
  }

  private static final class Store implements ProactiveDeliveryPendingStore {
    private int createAttempts;
    private boolean failNextCreate;
    private Optional<ProactiveDeliveryOperation> operation = Optional.empty();

    @Override
    public ProactiveDeliveryOperation create(
        ProactiveDeliveryOperation value,
        ApprovalInboxEntry approval,
        EncryptedProactiveDeliveryCapsule capsule) {
      createAttempts++;
      if (failNextCreate) {
        failNextCreate = false;
        throw new ProactiveDeliveryPreparationException();
      }
      operation = Optional.of(value);
      return value;
    }

    @Override
    public String toString() {
      return "Store[operation=" + operation.map(Object::toString).orElse("absent") + "]";
    }
  }
}
