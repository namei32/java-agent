package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlTurnRefGenerator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
}
