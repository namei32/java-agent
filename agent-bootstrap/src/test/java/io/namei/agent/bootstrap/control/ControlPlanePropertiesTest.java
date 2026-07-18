package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ControlPlanePropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsStrictDisabledDefaults() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          ControlPlaneProperties properties = context.getBean(ControlPlaneProperties.class);
          assertThat(properties.mode()).isEqualTo(ControlPlaneMode.DISABLED);
          assertThat(properties.sessionTtl()).isEqualTo(Duration.ofMinutes(15));
          assertThat(properties.maxSessions()).isEqualTo(4);
          assertThat(properties.maxActiveTurns()).isEqualTo(128);
          assertThat(properties.terminalRetention()).isEqualTo(Duration.ofMinutes(5));
          assertThat(properties.maxTerminalTombstones()).isEqualTo(1_024);
          assertThat(properties.maxSubscribers()).isEqualTo(8);
          assertThat(properties.subscriberBufferCapacity()).isEqualTo(64);
          assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
          assertThat(properties.streamMaxLifetime()).isEqualTo(Duration.ofMinutes(15));
          assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(2));
        });
  }

  @Test
  void acceptsLoopbackAndEveryApprovedHardBoundary() {
    runner
        .withPropertyValues(
            "agent.control-plane.mode=LOOPBACK",
            "agent.control-plane.session-ttl=1h",
            "agent.control-plane.max-sessions=16",
            "agent.control-plane.max-active-turns=1024",
            "agent.control-plane.terminal-retention=30m",
            "agent.control-plane.max-terminal-tombstones=4096",
            "agent.control-plane.max-subscribers=32",
            "agent.control-plane.subscriber-buffer-capacity=256",
            "agent.control-plane.heartbeat-interval=60s",
            "agent.control-plane.stream-max-lifetime=1h",
            "agent.control-plane.shutdown-timeout=10s")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ControlPlaneProperties properties = context.getBean(ControlPlaneProperties.class);
              assertThat(properties.mode()).isEqualTo(ControlPlaneMode.LOOPBACK);
              assertThat(properties.sessionTtl()).isEqualTo(Duration.ofHours(1));
              assertThat(properties.maxSessions()).isEqualTo(16);
              assertThat(properties.maxActiveTurns()).isEqualTo(1_024);
              assertThat(properties.terminalRetention()).isEqualTo(Duration.ofMinutes(30));
              assertThat(properties.maxTerminalTombstones()).isEqualTo(4_096);
              assertThat(properties.maxSubscribers()).isEqualTo(32);
              assertThat(properties.subscriberBufferCapacity()).isEqualTo(256);
              assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(60));
              assertThat(properties.streamMaxLifetime()).isEqualTo(Duration.ofHours(1));
              assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
            });
  }

  @Test
  void rejectsUnknownModeAndEveryOutOfRangeBudget() {
    for (String property :
        new String[] {
          "agent.control-plane.mode=loopback",
          "agent.control-plane.mode=UNKNOWN",
          "agent.control-plane.session-ttl=59s",
          "agent.control-plane.session-ttl=61m",
          "agent.control-plane.max-sessions=0",
          "agent.control-plane.max-sessions=17",
          "agent.control-plane.max-active-turns=0",
          "agent.control-plane.max-active-turns=1025",
          "agent.control-plane.terminal-retention=59s",
          "agent.control-plane.terminal-retention=31m",
          "agent.control-plane.max-terminal-tombstones=15",
          "agent.control-plane.max-terminal-tombstones=4097",
          "agent.control-plane.max-subscribers=0",
          "agent.control-plane.max-subscribers=33",
          "agent.control-plane.subscriber-buffer-capacity=0",
          "agent.control-plane.subscriber-buffer-capacity=257",
          "agent.control-plane.heartbeat-interval=4s",
          "agent.control-plane.heartbeat-interval=61s",
          "agent.control-plane.stream-max-lifetime=59s",
          "agent.control-plane.stream-max-lifetime=61m",
          "agent.control-plane.shutdown-timeout=99ms",
          "agent.control-plane.shutdown-timeout=11s"
        }) {
      runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ControlPlaneProperties.class)
  static class PropertiesConfiguration {}
}
