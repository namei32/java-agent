package io.namei.agent.bootstrap.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ProactiveInspectionPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsDisabledByDefaultAndAcceptsOnlyTheExplicitActiveRuntimeMode() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ProactiveInspectionProperties.class).toMode())
              .isEqualTo(ProactiveInspectionMode.DISABLED);
        });

    runner
        .withPropertyValues("agent.proactive-inspection.mode=ACTIVE_RUNTIME")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ProactiveInspectionProperties.class).toMode())
                  .isEqualTo(ProactiveInspectionMode.ACTIVE_RUNTIME);
            });

    for (String value : new String[] {"active_runtime", "READ_ONLY", "UNKNOWN"}) {
      runner
          .withPropertyValues("agent.proactive-inspection.mode=" + value)
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ProactiveInspectionProperties.class)
  static class PropertiesConfiguration {}
}
