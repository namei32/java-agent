package io.namei.agent.kernel.memory;

import java.util.Objects;

public record MemoryProfile(String selfModel, String longTermMemory, String recentContext) {
  public MemoryProfile {
    Objects.requireNonNull(selfModel, "selfModel");
    Objects.requireNonNull(longTermMemory, "longTermMemory");
    Objects.requireNonNull(recentContext, "recentContext");
  }

  public static MemoryProfile empty() {
    return new MemoryProfile("", "", "");
  }
}
