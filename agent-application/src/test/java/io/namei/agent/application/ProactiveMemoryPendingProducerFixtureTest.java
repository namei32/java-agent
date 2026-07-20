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
class ProactiveMemoryPendingProducerFixtureTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void consumesEveryR14P3ProactiveMemoryMutationCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("proactive/r14-proactive-memory-mutation-v1.json"));

    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("suite").asString())
        .isEqualTo("proactive/r14-proactive-memory-mutation-v1");
    assertThat(fixture.path("cases")).hasSize(7);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase, fixture.path("defaults"));
    }
  }

  @Test
  void concurrentPreparationClaimsOneCandidateForOneMemoryPendingOperation() throws Exception {
    var scenario = new Scenario();
    LocalProactiveCandidateResult candidate =
        candidate("fixed local text must never enter an outcome", false);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<ProactiveMemoryPreparationOutcome>> tasks =
          List.of(
              () -> scenario.producer.prepare(candidate, new TurnCancellationSource().token()),
              () -> scenario.producer.prepare(candidate, new TurnCancellationSource().token()));

      List<ProactiveMemoryPreparationOutcome.Kind> outcomes =
          executor.invokeAll(tasks).stream()
              .map(
                  future -> {
                    try {
                      return future.get().kind();
                    } catch (Exception exception) {
                      throw new AssertionError(exception);
                    }
                  })
              .toList();

      assertThat(outcomes)
          .containsExactlyInAnyOrder(
              ProactiveMemoryPreparationOutcome.Kind.PENDING,
              ProactiveMemoryPreparationOutcome.Kind.ALREADY_PREPARED);
    }
    assertThat(scenario.store.createAttempts).isEqualTo(1);
  }

  private static void verify(JsonNode testCase, JsonNode defaults) {
    JsonNode input = testCase.path("input");
    JsonNode expected = testCase.path("expected");
    var scenario = new Scenario();
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

    ProactiveMemoryPreparationOutcome outcome;
    if ("FAIL_ONCE".equals(input.path("store").asString())) {
      scenario.store.failNextCreate = true;
      assertThatThrownBy(() -> scenario.producer.prepare(candidate, cancellation.token()))
          .isInstanceOf(ProactiveMemoryPreparationException.class);
      outcome = scenario.producer.prepare(candidate, cancellation.token());
    } else {
      outcome = scenario.producer.prepare(candidate, cancellation.token());
    }
    if (input.path("repeat").asBoolean()) {
      ProactiveMemoryPreparationOutcome first = outcome;
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
    if (expected.has("leaksSourceText")) {
      String rendered = outcome + scenario.store.toString();
      assertThat(rendered).doesNotContain(defaults.path("sourceText").asString());
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
                ProactiveSourceKind.FIXED_LOCAL, "fixture-memory", sourceText),
            NOW));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private static final class Scenario {
    private final Store store = new Store();
    private final ProactiveMemoryPendingProducer producer =
        new ProactiveMemoryPendingProducer(
            store,
            () -> ProactiveMemoryOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
            () -> ApprovalInboxReference.of("BBBBBBBBBBBBBBBBBBBBBB"),
            new FixedIds(),
            new AesGcmProactiveMemoryCapsuleCipher(
                new SecretKeySpec(new byte[32], "AES"),
                "fixture-key",
                new SecureRandom(new byte[] {9})),
            CLOCK,
            Duration.ofMinutes(5));
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

  private static final class Store implements ProactiveMemoryPendingStore {
    private int createAttempts;
    private boolean failNextCreate;
    private Optional<ProactiveMemoryOperation> operation = Optional.empty();

    @Override
    public ProactiveMemoryOperation create(
        ProactiveMemoryOperation value,
        ApprovalInboxEntry approval,
        EncryptedProactiveMemoryCapsule capsule) {
      createAttempts++;
      if (failNextCreate) {
        failNextCreate = false;
        throw new ProactiveMemoryPreparationException();
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
