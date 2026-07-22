package io.namei.agent.kernel.plugin;

/** Python Plugin 生命周期中版本化、仅观察的子集。 */
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
