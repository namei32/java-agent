package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.proactive.DriftResult;
import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import io.namei.agent.kernel.proactive.ProactiveSourceKind;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("failure")
class ReadOnlyProactiveDecisionFailureTest {
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

  @Test
  void sourceFailureSkipsWithoutDriftMemoryOrExternalDelivery() {
    var driftCalled = new AtomicBoolean();
    var decision =
        runner(
                ignored -> {
                  throw new IllegalStateException("fixture source failure");
                },
                ignored -> {
                  driftCalled.set(true);
                  return DriftResult.detected("must not run");
                })
            .evaluate(lease(), TurnCancellation.none());

    assertThat(decision)
        .isEqualTo(ReadOnlyProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_SOURCE_INVALID));
    assertThat(driftCalled).isFalse();
    assertThat(decision.deliveryBoundary().allowsExternalDelivery()).isFalse();
    assertThat(decision.memoryMutationCount()).isZero();
  }

  @Test
  void driftFailureSkipsWithoutPendingApprovalOrExternalDelivery() {
    var decision =
        runner(
                ignored -> java.util.Optional.of(sourceItem()),
                ignored -> {
                  throw new IllegalStateException("fixture drift failure");
                })
            .evaluate(lease(), TurnCancellation.none());

    assertThat(decision)
        .isEqualTo(ReadOnlyProactiveDecision.skipped(ProactiveStableCode.DRIFT_READ_ONLY));
    assertThat(decision.deliveryBoundary().transportAuthorized()).isFalse();
    assertThat(decision.memoryMutationCount()).isZero();
  }

  @Test
  void cancellationDuringSourcePreventsDriftAndNeverCreatesAPendingProjection() {
    var cancellation = new TurnCancellationSource();
    var driftCalled = new AtomicBoolean();
    var decision =
        runner(
                ignored -> {
                  cancellation.cancel();
                  return java.util.Optional.empty();
                },
                ignored -> {
                  driftCalled.set(true);
                  return DriftResult.detected("must not run");
                })
            .evaluate(lease(), cancellation.token());

    assertThat(decision).isEqualTo(ReadOnlyProactiveDecision.cancelled());
    assertThat(driftCalled).isFalse();
    assertThat(decision.deliveryBoundary().allowsExternalDelivery()).isFalse();
    assertThat(decision.memoryMutationCount()).isZero();
  }

  private static ReadOnlyProactiveDecisionRunner runner(
      FixedLocalProactiveSource source, ReadOnlyDriftProbe drift) {
    return new ReadOnlyProactiveDecisionRunner(
        ignored -> ProactiveDecision.requested(),
        source,
        new ReadOnlyDriftRunner(drift),
        ProactiveAudit.disabled(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static ProactiveSourceItem sourceItem() {
    return ProactiveSourceItem.fixedLocal(
        ProactiveSourceKind.FIXED_LOCAL, "fixture-alert", "safe local item");
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
