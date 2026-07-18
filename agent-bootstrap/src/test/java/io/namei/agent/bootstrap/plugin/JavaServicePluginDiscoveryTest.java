package io.namei.agent.bootstrap.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.plugin.AgentPlugin;
import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginContractViolation;
import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginKind;
import io.namei.agent.kernel.plugin.PluginManifest;
import io.namei.agent.kernel.plugin.PluginStableCode;
import io.namei.agent.kernel.plugin.PluginTap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JavaServicePluginDiscoveryTest {
  @Test
  void disabledOrEmptyAllowlistNeverLoadsServiceProviders() {
    var loads = new AtomicInteger();
    var discovery = new JavaServicePluginDiscovery(() -> failIfLoaded(loads));

    assertThat(discovery.discover(PluginMode.DISABLED, List.of())).isEmpty();
    assertThat(discovery.discover(PluginMode.JAVA_SERVICE, List.of())).isEmpty();

    assertThat(loads).hasValue(0);
  }

  @Test
  void discoversOnlyExplicitJavaServicePluginsInConfiguredIdOrder() {
    var discovery =
        new JavaServicePluginDiscovery(
            () ->
                List.of(
                    plugin("beta", PluginKind.JAVA_SERVICE),
                    plugin("alpha", PluginKind.JAVA_SERVICE)));

    var bindings =
        discovery.discover(
            PluginMode.JAVA_SERVICE, List.of(PluginId.parse("alpha"), PluginId.parse("beta")));

    assertThat(bindings)
        .extracting(binding -> binding.manifest().id().value())
        .containsExactly("alpha", "beta");
  }

  @Test
  void rejectsDuplicateIdsOrWrongSourceKindBeforeActivatingAnyPlugin() {
    var duplicate =
        new JavaServicePluginDiscovery(
            () ->
                List.of(
                    plugin("same", PluginKind.JAVA_SERVICE),
                    plugin("same", PluginKind.JAVA_SERVICE)));
    var wrongKind =
        new JavaServicePluginDiscovery(
            () -> List.of(plugin("external", PluginKind.EXTERNAL_STDIO)));

    assertThatThrownBy(
            () -> duplicate.discover(PluginMode.JAVA_SERVICE, List.of(PluginId.parse("same"))))
        .isInstanceOf(PluginContractViolation.class)
        .extracting(error -> ((PluginContractViolation) error).code())
        .isEqualTo(PluginStableCode.PLUGIN_DUPLICATE_ID);
    assertThatThrownBy(
            () -> wrongKind.discover(PluginMode.JAVA_SERVICE, List.of(PluginId.parse("external"))))
        .isInstanceOf(PluginContractViolation.class)
        .extracting(error -> ((PluginContractViolation) error).code())
        .isEqualTo(PluginStableCode.PLUGIN_MANIFEST_INVALID);
  }

  private static List<AgentPlugin> failIfLoaded(AtomicInteger loads) {
    loads.incrementAndGet();
    throw new AssertionError("DISABLED 或空 allowlist 不得加载 Service Provider");
  }

  private static AgentPlugin plugin(String id, PluginKind kind) {
    return new AgentPlugin() {
      @Override
      public PluginManifest manifest() {
        return new PluginManifest(
            1, PluginId.parse(id), "1.0.0", 1, kind, List.of(PluginCapability.TURN_TAP));
      }

      @Override
      public PluginTap tap() {
        return event -> {};
      }
    };
  }
}
