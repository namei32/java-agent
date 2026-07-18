package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ActiveTurnObserver;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.application.control.ControlTurnRefGenerator;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class ControlPlaneConfigurationTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void loopbackCreatesOneRuntimeAndPublishesItAsTheProductionObserver() {
    ControlTurnRef reference = reference(1);
    new WebApplicationContextRunner()
        .withUserConfiguration(ControlPlaneConfiguration.class)
        .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
        .withBean(ControlTurnRefGenerator.class, () -> () -> reference)
        .withPropertyValues("agent.control-plane.mode=LOOPBACK", "server.address=127.0.0.1")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ControlPlaneRuntime.class);
              assertThat(context).hasSingleBean(ActiveTurnObserver.class);
              assertThat(context).hasSingleBean(ControlStreamTracker.class);
              assertThat(context).hasSingleBean(ControlPlaneShutdownCoordinator.class);
              assertThat(context.getBean(ControlPlaneAuditSink.class))
                  .isInstanceOf(SafeLoggingControlPlaneAuditSink.class);
              ControlPlaneRuntime runtime = context.getBean(ControlPlaneRuntime.class);
              assertThat(context.getBean(ActiveTurnObserver.class)).isSameAs(runtime);
              assertThat(runtime.eventHub().subscriberCount()).isZero();

              var source = new TurnCancellationSource();
              var registration =
                  runtime.register("telegram", ControlCancellationHandle.from(source), NOW);
              assertThat(registration.turnRef()).contains(reference);
              assertThat(runtime.registry().snapshot().activeTurns()).hasSize(1);
              registration.closeWithoutTerminal();
            });
  }

  private static ControlTurnRef reference(int lastByte) {
    byte[] bytes = new byte[16];
    bytes[15] = (byte) lastByte;
    return ControlTurnRef.fromBytes(bytes);
  }
}
