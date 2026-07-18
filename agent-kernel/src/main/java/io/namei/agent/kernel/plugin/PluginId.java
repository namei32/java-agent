package io.namei.agent.kernel.plugin;

import java.util.Objects;
import java.util.regex.Pattern;

public record PluginId(String value) implements Comparable<PluginId> {
  private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]{0,62}");

  public PluginId {
    if (value == null || !VALID.matcher(value).matches()) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
  }

  public static PluginId parse(String value) {
    return new PluginId(value);
  }

  @Override
  public int compareTo(PluginId other) {
    return value.compareTo(Objects.requireNonNull(other, "other").value);
  }

  @Override
  public String toString() {
    return "PluginId[<redacted>]";
  }
}
