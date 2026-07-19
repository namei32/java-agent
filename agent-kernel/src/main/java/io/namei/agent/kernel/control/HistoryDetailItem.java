package io.namei.agent.kernel.control;

import java.time.Instant;
import java.util.Objects;

/** A content-free, safe history metadata item. */
public record HistoryDetailItem(HistoryVisibleRole role, Instant occurredAt) {
  public HistoryDetailItem {
    role = Objects.requireNonNull(role, "role");
    occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
