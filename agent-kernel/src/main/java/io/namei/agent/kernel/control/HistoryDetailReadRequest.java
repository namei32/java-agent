package io.namei.agent.kernel.control;

import java.time.Instant;
import java.util.Objects;

/** Fixed, server-authorized read inputs; it carries no SQL, Session, query, or content budget. */
public record HistoryDetailReadRequest(
    HistoryDetailRef reference, int pageSize, int offset, Instant observedAt) {
  public static final int DEFAULT_PAGE_SIZE = 10;
  public static final int MAXIMUM_OFFSET = 1_024;

  public HistoryDetailReadRequest(HistoryDetailRef reference, int pageSize, Instant observedAt) {
    this(reference, pageSize, 0, observedAt);
  }

  public HistoryDetailReadRequest {
    reference = Objects.requireNonNull(reference, "reference");
    observedAt = Objects.requireNonNull(observedAt, "observedAt");
    if (pageSize < 1 || pageSize > HistoryDetailPage.MAXIMUM_PAGE_SIZE) {
      throw new IllegalArgumentException("控制历史详情分页大小超出批准范围");
    }
    if (offset < 0 || offset > MAXIMUM_OFFSET) {
      throw new IllegalArgumentException("控制历史详情分页偏移超出批准范围");
    }
  }
}
