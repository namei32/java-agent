package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.evidence.ConversationEvidenceMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ConversationEvidencePropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsDisabledByDefaultAndAcceptsOnlyTheExplicitReadOnlyMode() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ConversationEvidenceProperties.class).toMode())
              .isEqualTo(ConversationEvidenceMode.DISABLED);
        });

    runner
        .withPropertyValues("agent.conversation-evidence.mode=CURRENT_SESSION_READ_ONLY")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ConversationEvidenceProperties.class).toMode())
                  .isEqualTo(ConversationEvidenceMode.CURRENT_SESSION_READ_ONLY);
            });

    for (String value : new String[] {"current_session_read_only", "READ_ONLY", "UNKNOWN"}) {
      runner
          .withPropertyValues("agent.conversation-evidence.mode=" + value)
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ConversationEvidenceProperties.class)
  static class PropertiesConfiguration {}
}
