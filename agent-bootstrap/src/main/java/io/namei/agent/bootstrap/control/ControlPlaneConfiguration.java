package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlTurnRefGenerator;
import io.namei.agent.bootstrap.channel.ChannelHost;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ControlPlaneProperties.class)
public class ControlPlaneConfiguration {
  private static final String PREFIX = "agent.control-plane";

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  ControlPlaneLoopbackGuard controlPlaneLoopbackGuard(
      ApplicationContext context, Environment environment) {
    return new ControlPlaneLoopbackGuard(context, environment);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  ControlPlaneRuntime controlPlaneRuntime(
      ControlPlaneProperties properties,
      ControlPlaneLoopbackGuard guard,
      ObjectProvider<Clock> clocks,
      ObjectProvider<ControlTurnRefGenerator> references) {
    if (guard == null) {
      throw new IllegalStateException("控制面 Loopback Guard 缺失");
    }
    return new ControlPlaneRuntime(
        properties,
        clocks.getIfAvailable(Clock::systemUTC),
        references.getIfAvailable(ControlTurnRefGenerator::secure));
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  @ConditionalOnMissingBean(ControlRandomSource.class)
  ControlRandomSource controlRandomSource() {
    return ControlRandomSource.secure();
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  @ConditionalOnMissingBean(ControlRequestIdGenerator.class)
  ControlRequestIdGenerator controlRequestIdGenerator() {
    return ControlRequestIdGenerator.secure();
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  @ConditionalOnMissingBean(ControlPlaneAuditSink.class)
  ControlPlaneAuditSink controlPlaneAuditSink() {
    return ControlPlaneAuditSink.disabled();
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  ControlPlaneAudit controlPlaneAudit(ObjectProvider<Clock> clocks, ControlPlaneAuditSink sink) {
    return new ControlPlaneAudit(clocks.getIfAvailable(Clock::systemUTC), sink);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  OperatorSessionStore operatorSessionStore(
      ControlPlaneProperties properties,
      ObjectProvider<Clock> clocks,
      ControlRandomSource random,
      ControlPlaneRuntime runtime) {
    return new OperatorSessionStore(
        clocks.getIfAvailable(Clock::systemUTC),
        random,
        properties.sessionTtl(),
        properties.maxSessions(),
        runtime.eventHub()::closeActor);
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  LoopbackRequestGuard loopbackRequestGuard() {
    return new LoopbackRequestGuard();
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  ControlPlaneSecurityFilter controlPlaneSecurityFilter(
      LoopbackRequestGuard guard,
      OperatorSessionStore sessions,
      ControlPlaneAudit audit,
      ControlRequestIdGenerator requestIds,
      ObjectProvider<ObjectMapper> objectMappers) {
    return new ControlPlaneSecurityFilter(
        guard, sessions, audit, requestIds, objectMappers.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  ControlPlaneStatusService controlPlaneStatusService(
      ObjectProvider<Clock> clocks,
      ObjectProvider<ChannelHost> hosts,
      ControlPlaneRuntime runtime,
      ControlPlaneProperties properties) {
    ChannelHost host = hosts.getIfAvailable(() -> new ChannelHost(java.util.List.of()));
    return new ControlPlaneStatusService(
        clocks.getIfAvailable(Clock::systemUTC), host, runtime, properties);
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "mode", havingValue = "LOOPBACK")
  @ConditionalOnMissingBean(ControlSseWriterFactory.class)
  ControlSseWriterFactory controlSseWriterFactory(ObjectProvider<ObjectMapper> objectMappers) {
    return new ServletControlSseWriterFactory(objectMappers.getIfAvailable(ObjectMapper::new));
  }
}
