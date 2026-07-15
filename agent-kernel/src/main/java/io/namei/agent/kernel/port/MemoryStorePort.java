package io.namei.agent.kernel.port;

import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import java.util.List;

public interface MemoryStorePort {
  long candidateCount(MemoryScope scope);

  List<MemoryItem> loadCandidates(MemorySearchRequest request);

  List<MemoryItem> list(MemoryScope scope, int limit);
}
