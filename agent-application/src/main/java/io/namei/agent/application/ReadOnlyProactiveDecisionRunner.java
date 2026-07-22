package io.namei.agent.application;

import io.namei.agent.kernel.proactive.DriftRequest;
import io.namei.agent.kernel.proactive.DriftResult;
import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** P1 的仅本地决策链。它绝不启动 Scheduler、调用模型、写入 Memory、创建 Approval 请求或调用 Delivery/Transport 路径。 */
public final class ReadOnlyProactiveDecisionRunner {
  private static final int MAX_DRIFT_SUMMARY_CODE_POINTS = 512;

  private final ProactiveGate gate;
  private final FixedLocalProactiveSource source;
  private final ReadOnlyDriftRunner drift;
  private final ProactiveAudit audit;
  private final Clock clock;

  public ReadOnlyProactiveDecisionRunner(
      ProactiveGate gate,
      FixedLocalProactiveSource source,
      ReadOnlyDriftRunner drift,
      ProactiveAudit audit,
      Clock clock) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.source = Objects.requireNonNull(source, "source");
    this.drift = Objects.requireNonNull(drift, "drift");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public ReadOnlyProactiveDecision evaluate(
      ProactiveJobLease lease, TurnCancellation cancellation) {
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(cancellation, "cancellation");
    if (cancellation.isCancellationRequested()) {
      return ReadOnlyProactiveDecision.cancelled();
    }
    if (!lease.expiresAt().isAfter(clock.instant())) {
      return project(
          lease, ReadOnlyProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_LEASE_LOST));
    }

    ProactiveDecision gateDecision = gate.evaluate(lease);
    audit(lease, ProactiveAuditEvent.Action.GATE, gateDecision.code());
    if (gateDecision.kind() == ProactiveDecision.Kind.SKIP) {
      return project(lease, ReadOnlyProactiveDecision.skipped(gateDecision.code().orElseThrow()));
    }
    if (cancellation.isCancellationRequested()) {
      return ReadOnlyProactiveDecision.cancelled();
    }

    Optional<ProactiveSourceItem> localSource;
    try {
      localSource = source.next(cancellation);
    } catch (RuntimeException failure) {
      audit(
          lease,
          ProactiveAuditEvent.Action.SOURCE,
          Optional.of(ProactiveStableCode.PROACTIVE_SOURCE_INVALID));
      return project(
          lease, ReadOnlyProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_SOURCE_INVALID));
    }
    if (cancellation.isCancellationRequested()) {
      return ReadOnlyProactiveDecision.cancelled();
    }
    if (localSource == null || localSource.isEmpty()) {
      audit(
          lease,
          ProactiveAuditEvent.Action.SOURCE,
          Optional.of(ProactiveStableCode.PROACTIVE_NO_SOURCE));
      return project(
          lease, ReadOnlyProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_NO_SOURCE));
    }
    audit(lease, ProactiveAuditEvent.Action.SOURCE, Optional.empty());

    DriftResult driftResult;
    try {
      driftResult =
          drift.inspect(
              new DriftRequest(
                  lease.job().jobRef(), lease.job().targetHash(), MAX_DRIFT_SUMMARY_CODE_POINTS),
              cancellation);
    } catch (RuntimeException failure) {
      audit(
          lease,
          ProactiveAuditEvent.Action.DRIFT,
          Optional.of(ProactiveStableCode.DRIFT_READ_ONLY));
      return project(lease, ReadOnlyProactiveDecision.skipped(ProactiveStableCode.DRIFT_READ_ONLY));
    }
    if (cancellation.isCancellationRequested()
        || driftResult.status() == DriftResult.Status.CANCELLED) {
      return ReadOnlyProactiveDecision.cancelled();
    }
    if (driftResult.status() == DriftResult.Status.CLEAN) {
      audit(
          lease,
          ProactiveAuditEvent.Action.DRIFT,
          Optional.of(ProactiveStableCode.PROACTIVE_NO_DRIFT));
      return project(
          lease, ReadOnlyProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_NO_DRIFT));
    }
    audit(lease, ProactiveAuditEvent.Action.DRIFT, Optional.empty());
    return project(lease, ReadOnlyProactiveDecision.pendingApproval());
  }

  private ReadOnlyProactiveDecision project(
      ProactiveJobLease lease, ReadOnlyProactiveDecision decision) {
    audit(lease, ProactiveAuditEvent.Action.PROJECTION, decision.code());
    return decision;
  }

  private void audit(
      ProactiveJobLease lease,
      ProactiveAuditEvent.Action action,
      Optional<ProactiveStableCode> code) {
    audit.record(new ProactiveAuditEvent(lease.job().targetHash(), action, code, clock.instant()));
  }
}
