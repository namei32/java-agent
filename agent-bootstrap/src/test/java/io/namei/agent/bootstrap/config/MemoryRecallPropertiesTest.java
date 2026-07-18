package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.memory.MemoryRecallMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MemoryRecallPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsDisabledByDefaultAndAcceptsOnlyTheExplicitCurrentScopeMode() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(MemoryRecallProperties.class).toMode())
              .isEqualTo(MemoryRecallMode.DISABLED);
        });

    runner
        .withPropertyValues("agent.memory-recall.mode=CURRENT_SCOPE_READ_ONLY")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(MemoryRecallProperties.class).toMode())
                  .isEqualTo(MemoryRecallMode.CURRENT_SCOPE_READ_ONLY);
            });

    for (String value : new String[] {"current_scope_read_only", "READ_ONLY", "UNKNOWN"}) {
      runner
          .withPropertyValues("agent.memory-recall.mode=" + value)
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(MemoryRecallProperties.class)
  static class PropertiesConfiguration {}
}
