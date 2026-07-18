package io.namei.agent.application.plugin;

import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginStableCode;
import java.util.Objects;
import java.util.Optional;

public record PluginTapStatus(
    PluginId pluginId,
    PluginRuntimeState state,
    Optional<PluginStableCode> lastCode,
    long droppedEvents) {
  public PluginTapStatus {
    pluginId = Objects.requireNonNull(pluginId, "pluginId");
    state = Objects.requireNonNull(state, "state");
    lastCode = Objects.requireNonNull(lastCode, "lastCode");
    if (droppedEvents < 0) {
      throw new IllegalArgumentException("droppedEvents 不能为负数");
    }
  }
}
