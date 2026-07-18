package io.namei.agent.application.plugin;

import io.namei.agent.kernel.plugin.PluginManifest;
import io.namei.agent.kernel.plugin.PluginTap;
import java.util.Objects;

public record PluginTapBinding(PluginManifest manifest, int priority, PluginTap tap) {
  public PluginTapBinding {
    manifest = Objects.requireNonNull(manifest, "manifest");
    tap = Objects.requireNonNull(tap, "tap");
    if (priority < -1_000 || priority > 1_000) {
      throw new IllegalArgumentException("Plugin Tap priority 必须在 -1000..1000");
    }
  }
}
