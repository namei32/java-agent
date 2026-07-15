package io.namei.agent.kernel.memory;

import java.util.Objects;

public final class MemoryScope {
  private final String binding;

  public MemoryScope(String binding) {
    this.binding = MemoryValueRules.sha256(binding, "Memory Scope Binding");
  }

  public String binding() {
    return binding;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || (other instanceof MemoryScope scope && binding.equals(scope.binding));
  }

  @Override
  public int hashCode() {
    return Objects.hash(binding);
  }

  @Override
  public String toString() {
    return "MemoryScope[redacted]";
  }
}
