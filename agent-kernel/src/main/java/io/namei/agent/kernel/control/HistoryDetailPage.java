package io.namei.agent.kernel.control;

import java.util.List;
import java.util.Objects;

/** Bounded zero-content metadata page. */
public record HistoryDetailPage(List<HistoryDetailItem> items, boolean hasMore) {
  public static final int MAXIMUM_PAGE_SIZE = 20;

  public HistoryDetailPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (items.size() > MAXIMUM_PAGE_SIZE) {
      throw new IllegalArgumentException("控制历史详情分页超过批准上限");
    }
  }
}
