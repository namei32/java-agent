package io.namei.agent.kernel.plugin;

import java.util.List;
import java.util.Objects;

public record PluginManifest(
    int schemaVersion,
    PluginId id,
    String version,
    int apiVersion,
    PluginKind kind,
    List<PluginCapability> capabilities) {
  public PluginManifest {
    id = Objects.requireNonNull(id, "id");
    version = Objects.requireNonNull(version, "version");
    kind = Objects.requireNonNull(kind, "kind");
    capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    PluginContract.validate(schemaVersion, version, apiVersion, capabilities);
  }
}
