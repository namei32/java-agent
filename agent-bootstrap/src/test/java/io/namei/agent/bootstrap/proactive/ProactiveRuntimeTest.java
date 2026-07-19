package io.namei.agent.bootstrap.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.bootstrap.plugin.PluginRuntime;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProactiveRuntimeTest {
  @TempDir java.nio.file.Path tempDir;

  @Test
  void disabledDoesNotResolveOrCreateTheProactiveDatabase() {
    var workspace = tempDir.resolve("not-created");
    var properties = new ProactiveProperties("DISABLED", null, null, null, null, null, List.of());

    try (var runtime = ProactiveRuntime.start(properties, workspace, PluginRuntime.disabled())) {
      assertThat(runtime.active()).isFalse();
      assertThat(runtime.inspectionPort()).isEmpty();
    }

    assertThat(Files.exists(workspace)).isFalse();
  }

  @Test
  void localSqliteStartsOnlyWithAnExplicitHashOnlyAllowlistedPlan() {
    var workspace = tempDir.resolve("workspace");
    var plan =
        new ProactiveProperties.Plan(
            "daily-summary",
            "AT",
            Instant.parse("2026-07-19T00:00:00Z"),
            null,
            "a".repeat(64),
            "b".repeat(64),
            3);
    var properties =
        new ProactiveProperties(
            "LOCAL_SQLITE",
            "proactive-local",
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            List.of(plan));

    try (var runtime = ProactiveRuntime.start(properties, workspace, PluginRuntime.disabled())) {
      assertThat(runtime.active()).isTrue();
      assertThat(runtime.inspectionPort()).isPresent();
      assertThat(Files.isRegularFile(workspace.resolve("proactive/proactive-runtime.db"))).isTrue();
    }
  }

  @Test
  void localSqliteRefusesEmptyOrNonUppercaseModes() {
    assertThatThrownBy(
            () -> new ProactiveProperties("LOCAL_SQLITE", null, null, null, null, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ProactiveProperties("local_sqlite", null, null, null, null, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
