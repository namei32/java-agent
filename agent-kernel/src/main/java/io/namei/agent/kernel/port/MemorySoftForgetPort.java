package io.namei.agent.kernel.port;

import io.namei.agent.kernel.memory.MemoryForgetCommand;
import io.namei.agent.kernel.memory.MemoryForgetResult;

/** Narrow write boundary for the approved scope-bound, soft-supersede memory operation. */
public interface MemorySoftForgetPort {
  MemoryForgetResult softForget(MemoryForgetCommand command);
}
