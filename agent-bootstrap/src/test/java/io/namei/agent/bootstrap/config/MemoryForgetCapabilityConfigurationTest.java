package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.sqlite.AesGcmPendingOperationCapsuleCipher;
import io.namei.agent.adapter.sqlite.ApprovalInboxSchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcApprovalInbox;
import io.namei.agent.adapter.sqlite.JdbcPendingOperationStore;
import io.namei.agent.application.ApprovalInbox;
import io.namei.agent.application.MemoryForgetCapability;
import io.namei.agent.application.MemoryForgetControlService;
import io.namei.agent.application.MemoryForgetRecoveryCoordinator;
import io.namei.agent.application.PendingOperationKeyProvider;
import io.namei.agent.bootstrap.control.ApprovalInboxProperties;
import io.namei.agent.bootstrap.control.ControlPlaneAudit;
import io.namei.agent.bootstrap.control.ControlPlaneMode;
import io.namei.agent.bootstrap.control.ControlPlaneProperties;
import io.namei.agent.bootstrap.control.PendingOperationController;
import io.namei.agent.kernel.memory.MemoryRuntimeMode;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.MemorySoftForgetPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class MemoryForgetCapabilityConfigurationTest {
  @TempDir Path tempDir;

  @Test
  void defaultDisabledCreatesNoKeyStoreCapabilityOrDatabase() throws Exception {
    new WebApplicationContextRunner()
        .withUserConfiguration(
            MemoryForgetCapabilityConfiguration.class, PendingControllerFixture.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(MemoryForgetCapabilityProperties.class).mode())
                  .isEqualTo(MemoryForgetCapabilityMode.DISABLED);
              assertThat(context).doesNotHaveBean(PendingOperationKeyProvider.class);
              assertThat(context).doesNotHaveBean(AesGcmPendingOperationCapsuleCipher.class);
              assertThat(context).doesNotHaveBean(JdbcPendingOperationStore.class);
              assertThat(context).doesNotHaveBean(MemoryForgetCapability.class);
              assertThat(context).doesNotHaveBean(MemoryForgetRecoveryCoordinator.class);
              assertThat(context).doesNotHaveBean(MemoryForgetControlService.class);
              assertThat(context).doesNotHaveBean(PendingOperationController.class);
            });

    try (var files = Files.list(tempDir)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void nonServletRuntimeCannotWireTheCapabilityEvenWhenEveryModeStringIsExplicit() {
    new ApplicationContextRunner()
        .withUserConfiguration(MemoryForgetCapabilityConfiguration.class)
        .withBean(
            AgentProperties.class, () -> agentProperties(tempDir, MemoryRuntimeMode.JAVA_NATIVE))
        .withBean(ApprovalInboxProperties.class, () -> new ApprovalInboxProperties("LOOPBACK"))
        .withBean(ControlPlaneProperties.class, () -> controls(ControlPlaneMode.LOOPBACK))
        .withPropertyValues(
            "agent.capabilities.memory-forget.mode=LOOPBACK_APPROVAL",
            "agent.capabilities.memory-forget.capsule-key-id=test-key",
            "agent.capabilities.memory-forget.capsule-key-base64=" + key())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(PendingOperationKeyProvider.class);
              assertThat(context).doesNotHaveBean(MemoryForgetControlService.class);
            });
  }

  @Test
  void modeAndCapsuleKeyAreStrictAndNeverRendered() {
    assertThatThrownBy(() -> new MemoryForgetCapabilityProperties("loopback_approval", "id", key()))
        .isInstanceOf(IllegalArgumentException.class);

    var invalid = new MemoryForgetCapabilityProperties("LOOPBACK_APPROVAL", "id", "not-base64");
    assertThatThrownBy(invalid::currentKey).isInstanceOf(IllegalArgumentException.class);
    assertThat(invalid.toString()).doesNotContain("not-base64");
  }

  @Test
  void enabledModeFailsClosedWhenItsLoopbackAndJavaMemoryPrerequisitesAreNotAllPresent() {
    new WebApplicationContextRunner()
        .withUserConfiguration(MemoryForgetCapabilityConfiguration.class)
        .withBean(
            AgentProperties.class, () -> agentProperties(tempDir, MemoryRuntimeMode.JAVA_NATIVE))
        .withBean(ApprovalInboxProperties.class, () -> new ApprovalInboxProperties("LOOPBACK"))
        .withBean(ControlPlaneProperties.class, () -> controls(ControlPlaneMode.DISABLED))
        .withPropertyValues(
            "agent.capabilities.memory-forget.mode=LOOPBACK_APPROVAL",
            "agent.capabilities.memory-forget.capsule-key-id=test-key",
            "agent.capabilities.memory-forget.capsule-key-base64=" + key(),
            "agent.approval-inbox.mode=LOOPBACK",
            "agent.control-plane.mode=LOOPBACK")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasMessageContaining("Memory Forget Capability 要求 JAVA_NATIVE");
            });
  }

  @Test
  void exactExplicitPrerequisitesWireTheNarrowCapabilityWithoutRegisteringATool() {
    Path database = tempDir.resolve("approval-inbox.db");
    var schema = new ApprovalInboxSchemaInitializer(database, 5_000);
    schema.initialize();
    new WebApplicationContextRunner()
        .withUserConfiguration(
            MemoryForgetCapabilityConfiguration.class, PendingControllerFixture.class)
        .withBean(
            AgentProperties.class, () -> agentProperties(tempDir, MemoryRuntimeMode.JAVA_NATIVE))
        .withBean(ApprovalInboxProperties.class, () -> new ApprovalInboxProperties("LOOPBACK"))
        .withBean(ControlPlaneProperties.class, () -> controls(ControlPlaneMode.LOOPBACK))
        .withBean(ApprovalInboxSchemaInitializer.class, () -> schema)
        .withBean(ApprovalInbox.class, () -> new JdbcApprovalInbox(schema))
        .withBean(
            MemorySoftForgetPort.class,
            () ->
                command -> {
                  throw new AssertionError();
                })
        .withBean(SessionRepository.class, EmptySessions::new)
        .withBean(ControlPlaneAudit.class, ControlPlaneAudit::disabled)
        .withPropertyValues(
            "agent.capabilities.memory-forget.mode=LOOPBACK_APPROVAL",
            "agent.capabilities.memory-forget.capsule-key-id=test-key",
            "agent.capabilities.memory-forget.capsule-key-base64=" + key(),
            "agent.approval-inbox.mode=LOOPBACK",
            "agent.control-plane.mode=LOOPBACK")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(PendingOperationKeyProvider.class);
              assertThat(context).hasSingleBean(AesGcmPendingOperationCapsuleCipher.class);
              assertThat(context).hasSingleBean(JdbcPendingOperationStore.class);
              assertThat(context).hasSingleBean(MemoryForgetCapability.class);
              assertThat(context).hasSingleBean(MemoryForgetRecoveryCoordinator.class);
              assertThat(context).hasSingleBean(MemoryForgetControlService.class);
              assertThat(context).hasSingleBean(PendingOperationController.class);
              assertThat(context.getBean(PendingOperationKeyProvider.class).toString())
                  .doesNotContain(key());
            });
    assertThat(database).isRegularFile();
  }

  private static AgentProperties agentProperties(Path workspace, MemoryRuntimeMode mode) {
    return new AgentProperties(
        workspace,
        null,
        null,
        null,
        null,
        new AgentProperties.Memory(mode, 65_536, 100_000, 20_000, null, null));
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

  private static String key() {
    return Base64.getEncoder().encodeToString(new byte[32]);
  }

  private static final class EmptySessions implements SessionRepository {
    @Override
    public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, java.util.List.of(), 0);
    }

    @Override
    public void appendTurn(String sessionId, PersistedTurn turn) {
      throw new UnsupportedOperationException();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @Import(PendingOperationController.class)
  static class PendingControllerFixture {}
}
