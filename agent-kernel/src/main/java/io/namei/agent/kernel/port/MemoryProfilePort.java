package io.namei.agent.kernel.port;

import io.namei.agent.kernel.memory.MemoryProfile;

@FunctionalInterface
public interface MemoryProfilePort {
  MemoryProfile load();

  static MemoryProfilePort empty() {
    return MemoryProfile::empty;
  }
}
