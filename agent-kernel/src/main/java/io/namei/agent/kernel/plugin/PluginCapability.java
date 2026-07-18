package io.namei.agent.kernel.plugin;

public enum PluginCapability {
  TURN_TAP,
  TOOL_TAP,
  PROACTIVE_TAP,
  LIFECYCLE_TAP;

  public static PluginCapability parse(String value) {
    try {
      return PluginCapability.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_CAPABILITY_UNAVAILABLE);
    }
  }
}
