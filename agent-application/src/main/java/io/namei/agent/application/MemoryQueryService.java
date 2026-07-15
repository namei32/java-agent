package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.port.MemoryStorePort;
import java.util.List;
import java.util.Objects;

public final class MemoryQueryService {
  private static final int LIST_LIMIT = 100;

  private final MemoryStorePort store;

  public MemoryQueryService(MemoryStorePort store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public List<MemoryView> list(String sessionId) {
    var scope = MemoryManagementRules.scope(sessionId);
    List<MemoryItem> items = Objects.requireNonNull(store.list(scope, LIST_LIMIT), "items");
    return items.stream().map(MemoryView::from).toList();
  }
}
