package io.namei.agent.bootstrap.http;

import io.namei.agent.application.MemoryView;
import java.util.List;
import java.util.Objects;

public record MemoryListHttpResponse(List<MemoryView> memories) {
  public MemoryListHttpResponse {
    memories = List.copyOf(Objects.requireNonNull(memories, "memories"));
  }
}
