package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import java.util.Optional;

/** P1 唯一的 Source Port。它不具备目标、Session、URL、文件系统、MCP、Provider 或网络能力，仅供注入本地 Fixture 使用。 */
@FunctionalInterface
public interface FixedLocalProactiveSource {
  Optional<ProactiveSourceItem> next(TurnCancellation cancellation);

  static FixedLocalProactiveSource empty() {
    return ignored -> Optional.empty();
  }
}
