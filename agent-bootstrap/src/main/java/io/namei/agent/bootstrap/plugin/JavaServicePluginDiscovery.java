package io.namei.agent.bootstrap.plugin;

import io.namei.agent.application.plugin.PluginTapBinding;
import io.namei.agent.kernel.plugin.AgentPlugin;
import io.namei.agent.kernel.plugin.PluginCatalog;
import io.namei.agent.kernel.plugin.PluginContract;
import io.namei.agent.kernel.plugin.PluginContractViolation;
import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginKind;
import io.namei.agent.kernel.plugin.PluginManifest;
import io.namei.agent.kernel.plugin.PluginStableCode;
import io.namei.agent.kernel.plugin.PluginTap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 只发现应用 classpath 上的 Java SPI；不读取目录，也不处理外部进程。 */
public final class JavaServicePluginDiscovery {
  private final Supplier<List<AgentPlugin>> providers;

  public JavaServicePluginDiscovery(Supplier<List<AgentPlugin>> providers) {
    this.providers = Objects.requireNonNull(providers, "providers");
  }

  public static JavaServicePluginDiscovery classpath() {
    return new JavaServicePluginDiscovery(
        () ->
            ServiceLoader.load(AgentPlugin.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
  }

  public List<PluginTapBinding> discover(PluginMode mode, List<PluginId> enabledIds) {
    Objects.requireNonNull(mode, "mode");
    enabledIds = List.copyOf(Objects.requireNonNull(enabledIds, "enabledIds"));
    validateConfiguredIds(enabledIds);
    if (mode != PluginMode.JAVA_SERVICE || enabledIds.isEmpty()) {
      return List.of();
    }

    List<DiscoveredPlugin> plugins = loadProviders().stream().map(this::describe).toList();
    PluginCatalog.of(plugins.stream().map(DiscoveredPlugin::manifest).toList());
    Map<PluginId, DiscoveredPlugin> byId =
        plugins.stream()
            .collect(Collectors.toMap(plugin -> plugin.manifest().id(), plugin -> plugin));

    return enabledIds.stream()
        .map(
            id -> {
              DiscoveredPlugin plugin = byId.get(id);
              if (plugin == null) {
                throw new PluginContractViolation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
              }
              return new PluginTapBinding(plugin.manifest(), 0, plugin.tap());
            })
        .toList();
  }

  private List<AgentPlugin> loadProviders() {
    try {
      List<AgentPlugin> loaded = providers.get();
      if (loaded == null || loaded.stream().anyMatch(Objects::isNull)) {
        throw new PluginContractViolation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
      }
      return List.copyOf(loaded);
    } catch (PluginContractViolation violation) {
      throw violation;
    } catch (RuntimeException | ServiceConfigurationError failure) {
      throw new PluginContractViolation(PluginStableCode.PLUGIN_EXECUTION_FAILED);
    }
  }

  private DiscoveredPlugin describe(AgentPlugin plugin) {
    PluginManifest manifest = plugin.manifest();
    if (manifest == null || manifest.kind() != PluginKind.JAVA_SERVICE) {
      throw new PluginContractViolation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
    PluginContract.validate(manifest);
    PluginTap tap = plugin.tap();
    if (tap == null) {
      throw new PluginContractViolation(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
    return new DiscoveredPlugin(manifest, tap);
  }

  private static void validateConfiguredIds(List<PluginId> enabledIds) {
    if (enabledIds.stream().anyMatch(Objects::isNull)
        || new HashSet<>(enabledIds).size() != enabledIds.size()) {
      throw new PluginContractViolation(PluginStableCode.PLUGIN_DUPLICATE_ID);
    }
  }

  private record DiscoveredPlugin(PluginManifest manifest, PluginTap tap) {}
}
