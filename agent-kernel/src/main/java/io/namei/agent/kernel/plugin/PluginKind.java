package io.namei.agent.kernel.plugin;

public enum PluginKind {
  JAVA_SERVICE,
  EXTERNAL_STDIO;

  public static PluginKind parse(String value) {
    try {
      return PluginKind.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
  }
}
