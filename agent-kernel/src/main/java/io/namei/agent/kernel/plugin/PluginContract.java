package io.namei.agent.kernel.plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class PluginContract {
  public static final int CURRENT_VERSION = 1;
  public static final int CURRENT_API_VERSION = 1;
  public static final int MAX_PLUGIN_ID_LENGTH = 63;
  public static final int MAX_VERSION_LENGTH = 64;

  private PluginContract() {}

  public static void validate(PluginManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    validate(
        manifest.schemaVersion(),
        manifest.version(),
        manifest.apiVersion(),
        manifest.capabilities());
  }

  static void validate(
      int schemaVersion, String version, int apiVersion, List<PluginCapability> capabilities) {
    if (schemaVersion != CURRENT_VERSION) {
      throw violation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
    if (apiVersion != CURRENT_API_VERSION) {
      throw violation(PluginStableCode.PLUGIN_API_INCOMPATIBLE);
    }
    if (version.isBlank()
        || version.length() > MAX_VERSION_LENGTH
        || version.chars().anyMatch(Character::isISOControl)) {
      throw violation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
    if (new HashSet<>(capabilities).size() != capabilities.size()) {
      throw violation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
  }

  static PluginContractViolation violation(PluginStableCode code) {
    return new PluginContractViolation(code);
  }
}
