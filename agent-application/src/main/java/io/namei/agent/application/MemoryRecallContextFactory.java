package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemoryType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Binds a Tool invocation to ChatService's already derived private session binding. */
public final class MemoryRecallContextFactory {
  private static final MemoryRecallContextFactory DISABLED = new MemoryRecallContextFactory(null);

  private final ReadOnlyMemoryRecallService service;

  private MemoryRecallContextFactory(ReadOnlyMemoryRecallService service) {
    this.service = service;
  }

  public static MemoryRecallContextFactory enabled(ReadOnlyMemoryRecallService service) {
    return new MemoryRecallContextFactory(Objects.requireNonNull(service, "service"));
  }

  public static MemoryRecallContextFactory disabled() {
    return DISABLED;
  }

  public ToolInvocationContext forSessionBinding(
      String sessionBinding, ToolInvocationContext currentContext) {
    Objects.requireNonNull(sessionBinding, "sessionBinding");
    Objects.requireNonNull(currentContext, "currentContext");
    if (service == null) {
      return currentContext;
    }
    return currentContext.withMemoryRecall(new BoundScope(service, sessionBinding));
  }

  private record BoundScope(ReadOnlyMemoryRecallService service, String sessionBinding)
      implements MemoryRecallScope {
    private BoundScope {
      Objects.requireNonNull(service, "service");
      Objects.requireNonNull(sessionBinding, "sessionBinding");
    }

    @Override
    public List<MemorySearchHit> recall(String query, Optional<MemoryType> memoryType, int limit) {
      return service.recall(sessionBinding, query, memoryType, limit);
    }
  }
}
