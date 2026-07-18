package io.namei.agent.bootstrap.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginContractViolation;
import io.namei.agent.kernel.plugin.PluginStableCode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PluginPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void defaultsToDisabledWithBoundedBudgetsAndNoConfiguredPlugin() {
    var properties = new PluginProperties(null, null, null, null, null, null);

    assertThat(properties.mode()).isEqualTo(PluginMode.DISABLED);
    assertThat(properties.tapTimeout()).isEqualTo(Duration.ofMillis(500));
    assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(1));
    assertThat(properties.maxFrameBytes()).isEqualTo(65_536);
    assertThat(properties.javaServiceIds()).isEmpty();
    assertThat(properties.external()).isEmpty();
    assertThat(properties).hasToString("PluginProperties[mode=DISABLED, plugins=<configured>]");
  }

  @Test
  void acceptsOnlyStrictUppercaseModesAndSourceSpecificConfiguration() {
    var javaService =
        new PluginProperties(
            "JAVA_SERVICE",
            Duration.ofMillis(200),
            Duration.ofMillis(300),
            1024,
            List.of("observer"),
            List.of());

    assertThat(javaService.mode()).isEqualTo(PluginMode.JAVA_SERVICE);
    assertThat(javaService.javaServiceIds())
        .extracting(id -> id.value())
        .containsExactly("observer");
    assertThatThrownBy(
            () ->
                new PluginProperties(
                    "java_service", null, null, null, List.of("observer"), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PluginProperties(
                    "JAVA_SERVICE", null, null, null, List.of("observer"), List.of(external())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void externalPluginMustExplicitlyOptIntoApiV2ForLifecycleTap() {
    var v2 =
        new PluginProperties.External(
            "lifecycle", "2.0.0", 2, List.of("/bin/echo"), List.of("LIFECYCLE_TAP"));

    assertThat(v2.manifest().apiVersion()).isEqualTo(2);
    assertThat(v2.manifest().capabilities()).containsExactly(PluginCapability.LIFECYCLE_TAP);
    assertThat(external().manifest().apiVersion()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                new PluginProperties.External(
                    "legacy", "1.0.0", List.of("/bin/echo"), List.of("LIFECYCLE_TAP")))
        .isInstanceOf(PluginContractViolation.class)
        .extracting(error -> ((PluginContractViolation) error).code())
        .isEqualTo(PluginStableCode.PLUGIN_CAPABILITY_UNAVAILABLE);
  }

  @Test
  void bindsApiVersionTwoOnlyWhenItIsExplicitlyConfigured() {
    runner
        .withPropertyValues(
            "agent.plugins.mode=EXTERNAL_STDIO",
            "agent.plugins.external[0].id=lifecycle",
            "agent.plugins.external[0].version=2.0.0",
            "agent.plugins.external[0].api-version=2",
            "agent.plugins.external[0].command[0]=/bin/echo",
            "agent.plugins.external[0].capabilities[0]=LIFECYCLE_TAP")
        .run(
            context -> {
              var properties = context.getBean(PluginProperties.class);

              assertThat(properties.external()).hasSize(1);
              assertThat(properties.external().getFirst().manifest().apiVersion()).isEqualTo(2);
              assertThat(properties.external().getFirst().manifest().capabilities())
                  .containsExactly(PluginCapability.LIFECYCLE_TAP);
            });
  }

  @Test
  void bindsTheLegacyApiVersionWhenExternalApiVersionIsOmitted() {
    runner
        .withPropertyValues(
            "agent.plugins.mode=EXTERNAL_STDIO",
            "agent.plugins.external[0].id=legacy",
            "agent.plugins.external[0].version=1.0.0",
            "agent.plugins.external[0].command[0]=/bin/echo",
            "agent.plugins.external[0].capabilities[0]=TURN_TAP")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(PluginProperties.class)
                            .external()
                            .getFirst()
                            .manifest()
                            .apiVersion())
                    .isEqualTo(1));
  }

  private static PluginProperties.External external() {
    return new PluginProperties.External(
        "external", "1.0.0", List.of("/bin/echo"), List.of("TURN_TAP"));
  }

  @EnableConfigurationProperties(PluginProperties.class)
  static class PropertiesConfiguration {}
}
