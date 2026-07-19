package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.HistoryScopeCapability;
import java.util.Optional;

/** Bootstrap-only mapping from an authenticated operator to one already-bound opaque Scope. */
@FunctionalInterface
public interface ControlHistoryScopeResolver {
  Optional<HistoryScopeCapability> resolve(String actorRef);

  static ControlHistoryScopeResolver denied() {
    return actorRef -> Optional.empty();
  }
}
