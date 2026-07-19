package io.namei.agent.bootstrap.config;

import io.namei.agent.adapter.sqlite.AesGcmPendingOperationCapsuleCipher;
import io.namei.agent.adapter.sqlite.ApprovalInboxSchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcPendingOperationStore;
import io.namei.agent.application.ApprovalInbox;
import io.namei.agent.application.MemoryForgetCapability;
import io.namei.agent.application.MemoryForgetControlService;
import io.namei.agent.application.MemoryForgetRecoveryCoordinator;
import io.namei.agent.application.PendingOperationKey;
import io.namei.agent.application.PendingOperationKeyProvider;
import io.namei.agent.application.PendingOperationStore;
import io.namei.agent.bootstrap.control.ApprovalInboxMode;
import io.namei.agent.bootstrap.control.ApprovalInboxProperties;
import io.namei.agent.bootstrap.control.ControlPlaneMode;
import io.namei.agent.bootstrap.control.ControlPlaneProperties;
import io.namei.agent.kernel.memory.MemoryRuntimeMode;
import io.namei.agent.kernel.port.MemorySoftForgetPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production composition root for the approved {@code forget_memory} recovery path. It neither
 * exposes a Tool nor creates a worker: an explicit local authenticated resume remains required.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryForgetCapabilityProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MemoryForgetCapabilityConfiguration {
  private static final String PREFIX = "agent.capabilities.memory-forget";

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK_APPROVAL")
  PendingOperationKeyProvider memoryForgetPendingOperationKeyProvider(
      MemoryForgetCapabilityProperties properties,
      AgentProperties agentProperties,
      ApprovalInboxProperties approvalProperties,
      ControlPlaneProperties controlProperties) {
    requirePrerequisites(agentProperties, approvalProperties, controlProperties);
    return new StaticPendingOperationKeyProvider(properties.currentKey());
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK_APPROVAL")
  AesGcmPendingOperationCapsuleCipher memoryForgetPendingOperationCipher(
      PendingOperationKeyProvider keys) {
    return new AesGcmPendingOperationCapsuleCipher(keys);
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK_APPROVAL")
  JdbcPendingOperationStore memoryForgetPendingOperationStore(
      ApprovalInboxSchemaInitializer schema,
      ApprovalInbox inbox,
      AesGcmPendingOperationCapsuleCipher cipher) {
    Objects.requireNonNull(inbox, "approval inbox");
    return new JdbcPendingOperationStore(schema, cipher);
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK_APPROVAL")
  MemoryForgetCapability memoryForgetCapability(
      MemorySoftForgetPort store, ObjectProvider<Clock> clocks) {
    return new MemoryForgetCapability(store, clocks.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK_APPROVAL")
  MemoryForgetRecoveryCoordinator memoryForgetRecoveryCoordinator(
      PendingOperationStore operations,
      SessionRepository sessions,
      MemoryForgetCapability capability,
      ObjectProvider<Clock> clocks) {
    return new MemoryForgetRecoveryCoordinator(
        operations, sessions, capability, clocks.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK_APPROVAL")
  MemoryForgetControlService memoryForgetControlService(
      PendingOperationStore operations,
      SessionRepository sessions,
      MemoryForgetRecoveryCoordinator recovery,
      ObjectProvider<Clock> clocks) {
    return new MemoryForgetControlService(
        operations, sessions, recovery, clocks.getIfAvailable(Clock::systemUTC));
  }

  private static void requirePrerequisites(
      AgentProperties agentProperties,
      ApprovalInboxProperties approvalProperties,
      ControlPlaneProperties controlProperties) {
    if (agentProperties.memory().mode() != MemoryRuntimeMode.JAVA_NATIVE
        || approvalProperties.mode() != ApprovalInboxMode.LOOPBACK
        || controlProperties.mode() != ControlPlaneMode.LOOPBACK) {
      throw new IllegalStateException(
          "Memory Forget Capability 要求 JAVA_NATIVE 记忆、LOOPBACK 审批收件箱和 LOOPBACK 控制面");
    }
  }

  private static final class StaticPendingOperationKeyProvider
      implements PendingOperationKeyProvider {
    private final PendingOperationKey current;

    private StaticPendingOperationKeyProvider(PendingOperationKey current) {
      this.current = Objects.requireNonNull(current, "current");
    }

    @Override
    public PendingOperationKey current() {
      return current;
    }

    @Override
    public Optional<PendingOperationKey> findByKeyId(String keyId) {
      return current.keyId().equals(keyId) ? Optional.of(current) : Optional.empty();
    }

    @Override
    public String toString() {
      return "StaticPendingOperationKeyProvider[current=<redacted>]";
    }
  }
}
