package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import java.time.Clock;
import java.util.Objects;

/** 在可选投递前应用 Gate 和规划；Bootstrap 在 V1 中将投递绑定为 NoOp。 */
public final class ProactiveJobRunner implements ProactiveJobExecutor {
  private final ProactiveGate gate;
  private final ProactivePlanner planner;
  private final ProactiveDelivery delivery;
  private final ProactiveAudit audit;
  private final Clock clock;

  public ProactiveJobRunner(
      ProactiveGate gate,
      ProactivePlanner planner,
      ProactiveDelivery delivery,
      ProactiveAudit audit) {
    this(gate, planner, delivery, audit, Clock.systemUTC());
  }

  ProactiveJobRunner(
      ProactiveGate gate,
      ProactivePlanner planner,
      ProactiveDelivery delivery,
      ProactiveAudit audit,
      Clock clock) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.planner = Objects.requireNonNull(planner, "planner");
    this.delivery = Objects.requireNonNull(delivery, "delivery");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ProactiveJobState execute(ProactiveJobLease lease, TurnCancellation cancellation) {
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(cancellation, "cancellation");
    if (cancellation.isCancellationRequested()) {
      return ProactiveJobState.CANCELLED;
    }
    ProactiveDecision gated = gate.evaluate(lease);
    audit(lease, ProactiveAuditEvent.Action.GATE, gated);
    if (gated.kind() == ProactiveDecision.Kind.SKIP) {
      return ProactiveJobState.SKIPPED;
    }
    if (cancellation.isCancellationRequested()) {
      return ProactiveJobState.CANCELLED;
    }
    ProactiveDecision planned = planner.plan(lease, cancellation);
    if (planned == null) {
      return ProactiveJobState.FAILED;
    }
    audit(lease, ProactiveAuditEvent.Action.PLAN, planned);
    if (cancellation.isCancellationRequested()) {
      return ProactiveJobState.CANCELLED;
    }
    if (planned.kind() == ProactiveDecision.Kind.SKIP) {
      return ProactiveJobState.SKIPPED;
    }
    ProactiveDeliveryResult result = delivery.deliver(lease);
    audit(
        lease,
        ProactiveAuditEvent.Action.DELIVERY,
        result == ProactiveDeliveryResult.DELIVERED ? ProactiveDecision.requested() : planned);
    return result == ProactiveDeliveryResult.DELIVERED
        ? ProactiveJobState.SUCCEEDED
        : ProactiveJobState.SKIPPED;
  }

  private void audit(
      ProactiveJobLease lease, ProactiveAuditEvent.Action action, ProactiveDecision decision) {
    audit.record(
        new ProactiveAuditEvent(
            lease.job().targetHash(), action, decision.code(), clock.instant()));
  }
}
