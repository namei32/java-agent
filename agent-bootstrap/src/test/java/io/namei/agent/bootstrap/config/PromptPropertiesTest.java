package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.prompt.PromptMode;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PromptPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsMinimalDefaultsWithoutChangingExistingDeployments() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          PromptProperties properties = context.getBean(PromptProperties.class);
          assertThat(properties.mode()).isEqualTo(PromptMode.MINIMAL);
          assertThat(properties.zoneId()).isEqualTo(ZoneId.of("UTC"));
          assertThat(properties.budget().maxSystemTokens()).isEqualTo(100_000);
          assertThat(properties.budget().maxFrameTokens()).isEqualTo(100_000);
          assertThat(properties.budget().maxTotalTokens()).isEqualTo(200_000);
          assertThat(properties.budget().maxSections()).isEqualTo(9);
        });
  }

  @Test
  void acceptsAkashicCoreAndRejectsNonCanonicalModesZonesAndBudgets() {
    runner
        .withPropertyValues(
            "agent.prompt.mode=AKASHIC_CORE",
            "agent.prompt.zone-id=Asia/Shanghai",
            "agent.prompt.max-system-tokens=1",
            "agent.prompt.max-frame-tokens=1",
            "agent.prompt.max-total-tokens=2",
            "agent.prompt.max-sections=1")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              PromptProperties properties = context.getBean(PromptProperties.class);
              assertThat(properties.mode()).isEqualTo(PromptMode.AKASHIC_CORE);
              assertThat(properties.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
              assertThat(properties.budget().maxTotalTokens()).isEqualTo(2);
            });

    for (String property :
        new String[] {
          "agent.prompt.mode=akashic_core",
          "agent.prompt.mode=UNKNOWN",
          "agent.prompt.zone-id=not/a-zone",
          "agent.prompt.max-system-tokens=0",
          "agent.prompt.max-frame-tokens=100001",
          "agent.prompt.max-total-tokens=199999",
          "agent.prompt.max-sections=10"
        }) {
      runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PromptProperties.class)
  static class PropertiesConfiguration {}
}
