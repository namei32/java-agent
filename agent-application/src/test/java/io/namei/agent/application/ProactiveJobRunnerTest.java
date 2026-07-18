package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProactiveJobRunnerTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void busyGateSkipsBeforePlannerOrDelivery() {
    var plannerCalled = new AtomicBoolean();
    var deliveryCalled = new AtomicBoolean();
    var runner =
        new ProactiveJobRunner(
            new ProactiveSafetyGate(
                true,
                hash -> true,
                ProactiveDedupe.none(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5)),
            (lease, cancellation) -> {
              plannerCalled.set(true);
              return ProactiveDecision.requested();
            },
            lease -> {
              deliveryCalled.set(true);
              return ProactiveDeliveryResult.DELIVERED;
            },
            ProactiveAudit.disabled());

    assertThat(runner.execute(lease(), TurnCancellation.none()))
        .isEqualTo(ProactiveJobState.SKIPPED);
    assertThat(plannerCalled).isFalse();
    assertThat(deliveryCalled).isFalse();
  }

  @Test
  void requestedPlanUsesDefaultNoopDeliveryAndNeverSendsAnExternalMessage() {
    var runner =
        new ProactiveJobRunner(
            new ProactiveSafetyGate(
                true,
                ProactiveTargetBusyProbe.none(),
                ProactiveDedupe.none(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5)),
            (lease, cancellation) -> ProactiveDecision.requested(),
            ProactiveDelivery.noop(),
            ProactiveAudit.disabled());

    assertThat(runner.execute(lease(), TurnCancellation.none()))
        .isEqualTo(ProactiveJobState.SKIPPED);
  }

  @Test
  void cooldownAndKnownDedupeHaveStableSkipCodes() {
    var dedupe = ProactiveDedupe.known(lease().job().idempotencyKey());
    var gate =
        new ProactiveSafetyGate(
            true,
            ProactiveTargetBusyProbe.none(),
            dedupe,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5));

    assertThat(gate.evaluate(lease()))
        .isEqualTo(ProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_DUPLICATE));
    assertThat(
            new ProactiveSafetyGate(
                    true,
                    ProactiveTargetBusyProbe.none(),
                    ProactiveDedupe.none(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    Duration.ofMinutes(5))
                .evaluate(lease()))
        .isEqualTo(ProactiveDecision.requested());
  }

  private static ProactiveJobLease lease() {
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
        NOW.plusSeconds(30),
        2);
  }
}
