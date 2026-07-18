package io.namei.agent.kernel.plugin;

import java.util.Objects;

public final class PluginContractViolation extends IllegalArgumentException {
  private final PluginStableCode code;

  public PluginContractViolation(PluginStableCode code) {
    super("Plugin Contract 被拒绝: " + Objects.requireNonNull(code, "code").name());
    this.code = code;
  }

  public PluginStableCode code() {
    return code;
  }
}
