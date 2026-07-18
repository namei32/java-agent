package io.namei.agent.kernel.plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record PluginCatalog(List<PluginManifest> manifests) {
  public PluginCatalog {
    manifests = List.copyOf(Objects.requireNonNull(manifests, "manifests"));
  }

  public static PluginCatalog of(List<PluginManifest> manifests) {
    Objects.requireNonNull(manifests, "manifests");
    var copy = new ArrayList<>(manifests);
    for (PluginManifest manifest : copy) {
      Objects.requireNonNull(manifest, "manifest");
      PluginContract.validate(manifest);
    }
    if (copy.stream().map(PluginManifest::id).collect(java.util.stream.Collectors.toSet()).size()
        != copy.size()) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_DUPLICATE_ID);
    }
    copy.sort(Comparator.comparing(PluginManifest::id));
    return new PluginCatalog(copy);
  }
}
