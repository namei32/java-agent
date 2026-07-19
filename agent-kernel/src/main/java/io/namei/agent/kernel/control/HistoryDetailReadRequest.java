package io.namei.agent.kernel.control;

import java.time.Instant;
import java.util.Objects;

/** Fixed, server-authorized read inputs; it carries no SQL, Session, query, or content budget. */
public record HistoryDetailReadRequest(
    HistoryDetailRef reference, int pageSize, Instant observedAt) {
  public static final int DEFAULT_PAGE_SIZE = 10;

  public HistoryDetailReadRequest {
    reference = Objects.requireNonNull(reference, "reference");
    observedAt = Objects.requireNonNull(observedAt, "observedAt");
    if (pageSize < 1 || pageSize > HistoryDetailPage.MAXIMUM_PAGE_SIZE) {
      throw new IllegalArgumentException("控制历史详情分页大小超出批准范围");
    }
  }
}
