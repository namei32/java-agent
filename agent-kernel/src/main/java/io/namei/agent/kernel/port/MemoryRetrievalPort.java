package io.namei.agent.kernel.port;

import io.namei.agent.kernel.memory.MemoryRetrievalRequest;
import io.namei.agent.kernel.memory.MemoryRetrievalResult;

@FunctionalInterface
public interface MemoryRetrievalPort {
  MemoryRetrievalResult retrieve(MemoryRetrievalRequest request);

  static MemoryRetrievalPort disabled() {
    return request -> MemoryRetrievalResult.disabled();
  }
}
