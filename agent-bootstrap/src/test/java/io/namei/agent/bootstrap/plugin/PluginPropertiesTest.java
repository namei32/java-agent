package io.namei.agent.bootstrap.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginPropertiesTest {
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

  private static PluginProperties.External external() {
    return new PluginProperties.External(
        "external", "1.0.0", List.of("/bin/echo"), List.of("TURN_TAP"));
  }
}
