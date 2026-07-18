package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.skill.SkillCatalogMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SkillPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsDisabledDefaultsWithoutConfiguringOrReadingRoots() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          SkillProperties properties = context.getBean(SkillProperties.class);
          assertThat(properties.mode()).isEqualTo(SkillCatalogMode.DISABLED);
          assertThat(properties.builtinRoot()).isEmpty();
          assertThat(properties.maxSkills()).isEqualTo(64);
          assertThat(properties.maxFileBytes()).isEqualTo(65_536);
          assertThat(properties.maxCatalogCodePoints()).isEqualTo(32_768);
          assertThat(properties.maxActiveCodePoints()).isEqualTo(32_768);
          assertThat(properties.maxReadCodePoints()).isEqualTo(20_000);
        });
  }

  @Test
  void acceptsReadOnlyModeAndRejectsNonCanonicalOrUnsafeBudgets() {
    runner
        .withPropertyValues(
            "agent.skills.mode=READ_ONLY",
            "agent.skills.builtin-root=/opt/agent-skills",
            "agent.skills.max-skills=2",
            "agent.skills.max-file-bytes=1024",
            "agent.skills.max-catalog-code-points=100",
            "agent.skills.max-active-code-points=200",
            "agent.skills.max-read-code-points=300")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              SkillProperties properties = context.getBean(SkillProperties.class);
              assertThat(properties.mode()).isEqualTo(SkillCatalogMode.READ_ONLY);
              assertThat(properties.builtinRoot()).contains(Path.of("/opt/agent-skills"));
              assertThat(properties.maxReadCodePoints()).isEqualTo(300);
            });

    for (String property :
        new String[] {
          "agent.skills.mode=read_only",
          "agent.skills.mode=UNKNOWN",
          "agent.skills.max-skills=0",
          "agent.skills.max-skills=65",
          "agent.skills.max-file-bytes=0",
          "agent.skills.max-file-bytes=1048577",
          "agent.skills.max-catalog-code-points=0",
          "agent.skills.max-active-code-points=0",
          "agent.skills.max-read-code-points=0",
          "agent.skills.max-read-code-points=65537"
        }) {
      runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SkillProperties.class)
  static class PropertiesConfiguration {}
}
