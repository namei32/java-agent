package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.workspace.WorkspaceToolMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class WorkspaceToolPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsDefaultDisabledModeWithoutRequiringARoot() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          WorkspaceToolProperties properties = context.getBean(WorkspaceToolProperties.class);
          assertThat(properties.mode()).isEqualTo(WorkspaceToolMode.DISABLED);
          assertThat(properties.limits().maxSourceBytes()).isEqualTo(1_000_000);
          assertThat(properties.limits().maxLines()).isEqualTo(400);
          assertThat(properties.limits().maxOutputBytes()).isEqualTo(10_000);
          assertThat(properties.limits().maxOutputCodePoints()).isEqualTo(20_000);
          assertThat(properties.limits().maxDirectoryEntries()).isEqualTo(256);
        });
  }

  @Test
  void bindsExplicitReadOnlyModeAndRejectsRelaxedOrUnsafeValues() {
    runner
        .withPropertyValues(
            "agent.workspace-tools.mode=READ_ONLY",
            "agent.workspace-tools.root=/opt/agent-tool-root",
            "agent.workspace-tools.max-source-bytes=1024",
            "agent.workspace-tools.max-lines=2",
            "agent.workspace-tools.max-output-bytes=64",
            "agent.workspace-tools.max-output-code-points=64",
            "agent.workspace-tools.max-directory-entries=2")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              WorkspaceToolProperties properties = context.getBean(WorkspaceToolProperties.class);
              assertThat(properties.mode()).isEqualTo(WorkspaceToolMode.READ_ONLY);
              assertThat(properties.root()).isEqualTo(Path.of("/opt/agent-tool-root"));
              assertThat(properties.limits().maxSourceBytes()).isEqualTo(1024);
            });

    for (String property :
        new String[] {
          "agent.workspace-tools.mode=read_only",
          "agent.workspace-tools.max-source-bytes=0",
          "agent.workspace-tools.max-source-bytes=1000001",
          "agent.workspace-tools.max-lines=0",
          "agent.workspace-tools.max-lines=401",
          "agent.workspace-tools.max-output-bytes=10",
          "agent.workspace-tools.max-output-bytes=10001",
          "agent.workspace-tools.max-output-code-points=10",
          "agent.workspace-tools.max-output-code-points=20001",
          "agent.workspace-tools.max-directory-entries=0",
          "agent.workspace-tools.max-directory-entries=257"
        }) {
      runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(WorkspaceToolProperties.class)
  static class PropertiesConfiguration {}
}
