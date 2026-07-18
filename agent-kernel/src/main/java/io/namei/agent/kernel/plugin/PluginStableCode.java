package io.namei.agent.kernel.plugin;

public enum PluginStableCode {
  PLUGIN_DISABLED(false),
  PLUGIN_MANIFEST_INVALID(false),
  PLUGIN_DUPLICATE_ID(false),
  PLUGIN_API_INCOMPATIBLE(false),
  PLUGIN_CAPABILITY_UNAVAILABLE(false),
  PLUGIN_TIMEOUT(true),
  PLUGIN_PROTOCOL_INVALID(false),
  PLUGIN_PROCESS_EXITED(true),
  PLUGIN_SHUTTING_DOWN(true);

  private final boolean retryable;

  PluginStableCode(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }

  public static PluginStableCode parse(String value) {
    try {
      return PluginStableCode.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
  }
}
