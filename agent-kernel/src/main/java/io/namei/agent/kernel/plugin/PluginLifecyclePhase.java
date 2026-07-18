package io.namei.agent.kernel.plugin;

/** Versioned, observation-only subset of the Python Plugin lifecycle. */
public enum PluginLifecyclePhase {
  UNSPECIFIED,
  BEFORE_TURN,
  BEFORE_REASONING,
  AFTER_REASONING,
  BEFORE_TOOL_CALL,
  AFTER_TOOL_RESULT,
  AFTER_TURN;

  public static PluginLifecyclePhase parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
  }
}
