package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemoryType;
import java.util.List;
import java.util.Optional;

/** Opaque, authenticated scope exposed only to the current Tool invocation. */
interface MemoryRecallScope {
  List<MemorySearchHit> recall(String query, Optional<MemoryType> memoryType, int limit);
}
