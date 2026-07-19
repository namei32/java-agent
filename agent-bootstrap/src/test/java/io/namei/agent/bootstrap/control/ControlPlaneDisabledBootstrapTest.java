package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.control.ActiveTurnObserver;
import io.namei.agent.application.control.ActiveTurnRegistry;
import io.namei.agent.application.control.ControlEventHub;
import io.namei.agent.application.control.ControlTurnRefGenerator;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class ControlPlaneDisabledBootstrapTest {
  @TempDir Path temporaryDirectory;

  @Test
  void defaultDisabledCreatesNoRuntimeRegistrySubscriberRandomValueOrFile() throws Exception {
    var references = new CountingReferenceGenerator();
    new WebApplicationContextRunner()
        .withUserConfiguration(ControlPlaneConfiguration.class)
        .withBean(ControlTurnRefGenerator.class, () -> references)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ControlPlaneProperties.class).mode())
                  .isEqualTo(ControlPlaneMode.DISABLED);
              assertThat(context).doesNotHaveBean(ControlPlaneRuntime.class);
              assertThat(context).doesNotHaveBean(ControlHistoryDetailService.class);
              assertThat(context).doesNotHaveBean(ControlHistoryScopeResolver.class);
              assertThat(context).doesNotHaveBean(ActiveTurnObserver.class);
              assertThat(context).doesNotHaveBean(ActiveTurnRegistry.class);
              assertThat(context).doesNotHaveBean(ControlEventHub.class);
              assertThat(references.calls).hasValue(0);
            });

    try (var entries = Files.list(temporaryDirectory)) {
      assertThat(entries).isEmpty();
    }
  }

  @Test
  void applicationTemplateKeepsControlPlaneDisabledAndExposesOnlyApprovedBudgets()
      throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

    assertThat(yaml)
        .contains("mode: ${AGENT_CONTROL_PLANE_MODE:DISABLED}")
        .contains("session-ttl: ${AGENT_CONTROL_PLANE_SESSION_TTL:15m}")
        .contains("max-sessions: ${AGENT_CONTROL_PLANE_MAX_SESSIONS:4}")
        .contains("max-active-turns: ${AGENT_CONTROL_PLANE_MAX_ACTIVE_TURNS:128}")
        .contains("terminal-retention: ${AGENT_CONTROL_PLANE_TERMINAL_RETENTION:5m}")
        .contains("max-terminal-tombstones: ${AGENT_CONTROL_PLANE_MAX_TERMINAL_TOMBSTONES:1024}")
        .contains("max-subscribers: ${AGENT_CONTROL_PLANE_MAX_SUBSCRIBERS:8}")
        .contains(
            "subscriber-buffer-capacity: ${AGENT_CONTROL_PLANE_SUBSCRIBER_BUFFER_CAPACITY:64}")
        .contains("heartbeat-interval: ${AGENT_CONTROL_PLANE_HEARTBEAT_INTERVAL:15s}")
        .contains("stream-max-lifetime: ${AGENT_CONTROL_PLANE_STREAM_MAX_LIFETIME:15m}")
        .contains("shutdown-timeout: ${AGENT_CONTROL_PLANE_SHUTDOWN_TIMEOUT:2s}")
        .doesNotContain("CONTROL_PLANE_TOKEN", "CONTROL_PLANE_PASSWORD", "CONTROL_PLANE_ORIGIN");
  }

  private static final class CountingReferenceGenerator implements ControlTurnRefGenerator {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ControlTurnRef next() {
      calls.incrementAndGet();
      throw new AssertionError("Disabled 控制面不得生成 Turn 引用");
    }
  }
}
