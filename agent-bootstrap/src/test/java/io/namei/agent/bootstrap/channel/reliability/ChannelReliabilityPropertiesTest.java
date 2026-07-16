package io.namei.agent.bootstrap.channel.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ChannelReliabilityPropertiesTest {
  @Test
  void bindsFailClosedDefaults() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ChannelReliabilityProperties properties =
                  context.getBean(ChannelReliabilityProperties.class);
              assertThat(properties.mode()).isEqualTo(ChannelReliabilityMode.DISABLED);
              assertThat(properties.recoveryBatchSize()).isEqualTo(100);
              assertThat(properties.cleanupBatchSize()).isEqualTo(100);
              assertThat(properties.retention()).isEqualTo(Duration.ofDays(30));
              assertThat(properties.maxInboxRecords()).isEqualTo(100_000);
              assertThat(properties.maxDeliveryRecords()).isEqualTo(10_000);
            });
  }

  @Test
  void bindsSqliteAndAllApprovedBoundaries() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "agent.channels.reliability.mode=SQLITE",
            "agent.channels.reliability.recovery-batch-size=1",
            "agent.channels.reliability.cleanup-batch-size=1000",
            "agent.channels.reliability.retention=365d",
            "agent.channels.reliability.max-inbox-records=1000000",
            "agent.channels.reliability.max-delivery-records=100000")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ChannelReliabilityProperties properties =
                  context.getBean(ChannelReliabilityProperties.class);
              assertThat(properties.mode()).isEqualTo(ChannelReliabilityMode.SQLITE);
              assertThat(properties.recoveryBatchSize()).isOne();
              assertThat(properties.cleanupBatchSize()).isEqualTo(1_000);
              assertThat(properties.retention()).isEqualTo(Duration.ofDays(365));
              assertThat(properties.maxInboxRecords()).isEqualTo(1_000_000);
              assertThat(properties.maxDeliveryRecords()).isEqualTo(100_000);
            });
  }

  @Test
  void rejectsUnknownModesAndEveryOutOfRangeBudget() {
    assertInvalid("agent.channels.reliability.mode=sqlite");
    assertInvalid("agent.channels.reliability.recovery-batch-size=0");
    assertInvalid("agent.channels.reliability.recovery-batch-size=1001");
    assertInvalid("agent.channels.reliability.cleanup-batch-size=0");
    assertInvalid("agent.channels.reliability.cleanup-batch-size=1001");
    assertInvalid("agent.channels.reliability.retention=23h");
    assertInvalid("agent.channels.reliability.retention=366d");
    assertInvalid("agent.channels.reliability.max-inbox-records=999");
    assertInvalid("agent.channels.reliability.max-inbox-records=1000001");
    assertInvalid("agent.channels.reliability.max-delivery-records=99");
    assertInvalid("agent.channels.reliability.max-delivery-records=100001");
  }

  @Test
  void redactsConfigurationRendering() {
    var properties =
        new ChannelReliabilityProperties(
            ChannelReliabilityMode.SQLITE, 100, 100, Duration.ofDays(30), 100_000, 10_000);

    assertThat(properties.toString())
        .contains("mode=SQLITE")
        .doesNotContain("channel-ledger.db", "workspace");
    assertThatThrownBy(
            () ->
                new ChannelReliabilityProperties(
                    (ChannelReliabilityMode) null, 100, 100, Duration.ofDays(30), 100_000, 10_000))
        .isInstanceOf(NullPointerException.class);
  }

  private static void assertInvalid(String property) {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(property)
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ChannelReliabilityProperties.class)
  static class PropertiesConfiguration {}
}
