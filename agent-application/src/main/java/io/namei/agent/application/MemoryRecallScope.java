package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemoryType;
import java.util.List;
import java.util.Optional;

/** 仅向当前 Tool 调用暴露的不透明、已认证 Scope。 */
interface MemoryRecallScope {
  List<MemorySearchHit> recall(String query, Optional<MemoryType> memoryType, int limit);
}
