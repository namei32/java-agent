package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.ContextLimitRecoveryMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ContextLimitRecoveryPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsDisabledByDefaultAndAcceptsOnlyExplicitSafeLocalMode() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ContextLimitRecoveryProperties.class).toPolicy().mode())
              .isEqualTo(ContextLimitRecoveryMode.DISABLED);
        });

    runner
        .withPropertyValues("agent.context-limit-recovery.mode=SAFE_LOCAL")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ContextLimitRecoveryProperties.class).toPolicy().mode())
                  .isEqualTo(ContextLimitRecoveryMode.SAFE_LOCAL);
            });

    for (String value : new String[] {"safe_local", "LOCAL", "UNKNOWN"}) {
      runner
          .withPropertyValues("agent.context-limit-recovery.mode=" + value)
          .run(context -> assertThat(context).hasFailed());
    }

    assertThatThrownBy(() -> new ContextLimitRecoveryProperties(" SAFE_LOCAL"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContextLimitRecoveryProperties("SAFE_LOCAL "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ContextLimitRecoveryProperties.class)
  static class PropertiesConfiguration {}
}
