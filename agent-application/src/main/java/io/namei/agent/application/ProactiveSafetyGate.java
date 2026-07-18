package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** In-process safety gates; durable delivery dedupe remains owned by the channel ledger. */
public final class ProactiveSafetyGate implements ProactiveGate {
  private final boolean enabled;
  private final ProactiveTargetBusyProbe busy;
  private final ProactiveDedupe dedupe;
  private final Clock clock;
  private final Duration cooldown;
  private final ConcurrentHashMap<String, Instant> lastAccepted = new ConcurrentHashMap<>();

  public ProactiveSafetyGate(
      boolean enabled,
      ProactiveTargetBusyProbe busy,
      ProactiveDedupe dedupe,
      Clock clock,
      Duration cooldown) {
    this.enabled = enabled;
    this.busy = Objects.requireNonNull(busy, "busy");
    this.dedupe = Objects.requireNonNull(dedupe, "dedupe");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (cooldown == null || cooldown.isNegative() || cooldown.isZero()) {
      throw new IllegalArgumentException("Proactive cooldown 必须为正数");
    }
    this.cooldown = cooldown;
  }

  @Override
  public ProactiveDecision evaluate(ProactiveJobLease lease) {
    Objects.requireNonNull(lease, "lease");
    if (!enabled) {
      return ProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_DISABLED);
    }
    if (busy.isBusy(lease.job().targetHash())) {
      return ProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_TARGET_BUSY);
    }
    if (dedupe.isKnown(lease.job().idempotencyKey())) {
      return ProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_DUPLICATE);
    }
    Instant now = clock.instant();
    Instant previous = lastAccepted.get(lease.job().targetHash());
    if (previous != null && previous.plus(cooldown).isAfter(now)) {
      return ProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_COOLDOWN);
    }
    lastAccepted.put(lease.job().targetHash(), now);
    return ProactiveDecision.requested();
  }
}
