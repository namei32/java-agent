package io.namei.agent.application.plugin;

import io.namei.agent.kernel.plugin.PluginStableCode;
import java.util.Objects;
import java.util.Optional;

public record PluginTapAuditEvent(
    String pluginHash, PluginTapAuditAction action, Optional<PluginStableCode> code, long count) {
  public PluginTapAuditEvent {
    pluginHash = Objects.requireNonNull(pluginHash, "pluginHash");
    action = Objects.requireNonNull(action, "action");
    code = Objects.requireNonNull(code, "code");
    if (count < 0) {
      throw new IllegalArgumentException("count 不能为负数");
    }
  }
}
