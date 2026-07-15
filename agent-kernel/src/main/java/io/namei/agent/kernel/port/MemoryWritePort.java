package io.namei.agent.kernel.port;

import io.namei.agent.kernel.memory.MemoryDeleteCommand;
import io.namei.agent.kernel.memory.MemoryDeleteResult;
import io.namei.agent.kernel.memory.MemoryMutation;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.memory.MemoryWriteResult;
import java.util.Optional;

public interface MemoryWritePort {
  Optional<MemoryMutation> findMutation(MemoryMutationKey key);

  MemoryWriteResult upsert(MemoryWriteCommand command);

  MemoryDeleteResult delete(MemoryDeleteCommand command);
}
