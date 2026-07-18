package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.control.ActiveTurnRegistration;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ControlPlaneSseIT {
  @Test
  void streamsOnlyFutureSafeMessagesThenClosesAfterAuthoritativeTerminal() throws Exception {
    var fixture = new ControlPlaneSseControllerTest.Fixture();
    ActiveTurnRegistration registration = fixture.registration;
    var response = new MockHttpServletResponse();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var stream =
          executor.submit(
              () -> runStream(fixture.controller, fixture.turnRef, fixture.request(), response));
      awaitSubscriber(fixture.runtime);
      var route = new MessageRoute("telegram", "raw-route-secret");
      registration.observe(
          OutboundMessage.started("raw-turn-secret", "telegram:raw-session-secret", route));
      registration.observe(
          OutboundMessage.delta(
              "raw-turn-secret", "telegram:raw-session-secret", route, 1, "未来片段"));
      registration.observe(
          OutboundMessage.completed(
              "raw-turn-secret", "telegram:raw-session-secret", route, 2, "最终回答"));
      stream.get(2, TimeUnit.SECONDS);
    }

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString())
        .contains(
            "event: control.stream.opened.v1",
            "\"lastSequence\":null",
            "id: 1",
            "\"type\":\"TURN_STARTED\"",
            "id: 2",
            "未来片段",
            "id: 3",
            "最终回答")
        .doesNotContain("raw-turn-secret", "raw-session-secret", "raw-route-secret");
    assertThat(fixture.runtime.eventHub().subscriberCount()).isZero();
  }

  private static void awaitSubscriber(ControlPlaneRuntime runtime) {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (runtime.eventHub().subscriberCount() != 1 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(runtime.eventHub().subscriberCount()).isEqualTo(1);
  }

  private static void runStream(
      ControlPlaneSseController controller,
      String turnRef,
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    try {
      controller.events(turnRef, request, response);
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }
}
