package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import java.time.Instant;
import java.util.Objects;

/**
 * In-memory-only handoff between P2-A and a future P2-B producer.
 *
 * <p>The source text is deliberately package-private: it must not cross an adapter boundary, become
 * a log field, or be rendered by a status projection.
 */
final class LocalProactiveCandidate {
  private final ProactiveJobLease lease;
  private final ProactiveSourceItem source;
  private final Instant preparedAt;

  LocalProactiveCandidate(ProactiveJobLease lease, ProactiveSourceItem source, Instant preparedAt) {
    this.lease = Objects.requireNonNull(lease, "lease");
    this.source = Objects.requireNonNull(source, "source");
    this.preparedAt = Objects.requireNonNull(preparedAt, "preparedAt");
  }

  ProactiveJobLease lease() {
    return lease;
  }

  ProactiveSourceItem source() {
    return source;
  }

  Instant preparedAt() {
    return preparedAt;
  }

  @Override
  public String toString() {
    return "LocalProactiveCandidate[lease=<redacted>, source=<redacted>, preparedAt="
        + preparedAt
        + "]";
  }
}
