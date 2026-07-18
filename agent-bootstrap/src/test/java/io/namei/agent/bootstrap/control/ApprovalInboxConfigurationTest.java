package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.adapter.sqlite.ApprovalInboxSchemaInitializer;
import io.namei.agent.application.ApprovalInbox;
import io.namei.agent.bootstrap.config.AgentProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApprovalInboxConfigurationTest {
  @TempDir Path tempDir;

  @Test
  void defaultDisabledCreatesNoInboxDatabaseOrRuntimeBeans() throws Exception {
    new ApplicationContextRunner()
        .withUserConfiguration(ApprovalInboxConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ApprovalInboxProperties.class).mode())
                  .isEqualTo(ApprovalInboxMode.DISABLED);
              assertThat(context).doesNotHaveBean(ApprovalInbox.class);
              assertThat(context).doesNotHaveBean(ApprovalInboxSchemaInitializer.class);
              assertThat(context).doesNotHaveBean(ApprovalInboxControlService.class);
            });

    try (var entries = Files.list(tempDir)) {
      assertThat(entries).isEmpty();
    }
  }

  @Test
  void loopbackCreatesOnlyTheIsolatedInboxWhenControlPlaneIsAlsoLoopback() {
    new ApplicationContextRunner()
        .withUserConfiguration(ApprovalInboxConfiguration.class)
        .withBean(AgentProperties.class, () -> agentProperties(tempDir))
        .withBean(ControlPlaneProperties.class, () -> controls(ControlPlaneMode.LOOPBACK))
        .withPropertyValues("agent.approval-inbox.mode=LOOPBACK")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ApprovalInbox.class);
              assertThat(context).hasSingleBean(ApprovalInboxSchemaInitializer.class);
              assertThat(context).hasSingleBean(ApprovalInboxControlService.class);
              assertThat(tempDir.resolve("approval-inbox.db")).isRegularFile();
              assertThat(tempDir.resolve("sessions.db")).doesNotExist();
            });
  }

  @Test
  void loopbackInboxRefusesToStartWhenControlPlaneIsNotLoopback() {
    new ApplicationContextRunner()
        .withUserConfiguration(ApprovalInboxConfiguration.class)
        .withBean(AgentProperties.class, () -> agentProperties(tempDir))
        .withBean(ControlPlaneProperties.class, () -> controls(ControlPlaneMode.DISABLED))
        .withPropertyValues("agent.approval-inbox.mode=LOOPBACK")
        .run(context -> assertThat(context).hasFailed());
  }

  private static AgentProperties agentProperties(Path workspace) {
    return new AgentProperties(workspace, null, null, null, null, null);
  }

  private static ControlPlaneProperties controls(ControlPlaneMode mode) {
    return new ControlPlaneProperties(
        mode.name(),
        Duration.ofMinutes(15),
        4,
        128,
        Duration.ofMinutes(5),
        1_024,
        8,
        64,
        Duration.ofSeconds(15),
        Duration.ofMinutes(15),
        Duration.ofSeconds(2));
  }
}
