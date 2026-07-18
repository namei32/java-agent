package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import java.util.Optional;

/**
 * P1's only Source port. It has no target, session, URL, filesystem, MCP, Provider, or network
 * capability and is intended solely for injected local fixtures.
 */
@FunctionalInterface
public interface FixedLocalProactiveSource {
  Optional<ProactiveSourceItem> next(TurnCancellation cancellation);

  static FixedLocalProactiveSource empty() {
    return ignored -> Optional.empty();
  }
}
