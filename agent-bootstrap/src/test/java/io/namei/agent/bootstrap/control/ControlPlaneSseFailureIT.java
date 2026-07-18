package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.application.control.ControlSequencedEvent;
import io.namei.agent.application.control.ControlStreamOpening;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Tag("failure")
class ControlPlaneSseFailureIT {
  @Test
  void writerFailureReleasesOnlyItsSubscriptionAndNeverCancelsTurn() throws Exception {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var source = new TurnCancellationSource();
    var registration =
        runtime.register(
            "telegram",
            ControlCancellationHandle.from(source),
            java.time.Instant.parse("2026-07-18T00:00:00Z"));
    String turnRef = registration.turnRef().orElseThrow().value();
    var sessions =
        new OperatorSessionStore(
            java.time.Clock.systemUTC(),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            runtime.eventHub()::closeActor);
    OperatorSessionCreated created = sessions.create();
    OperatorSessionPrincipal principal = sessions.authenticate(created.accessToken()).orElseThrow();
    var opened = new CountDownLatch(1);
    ControlSseWriterFactory failingWriter =
        response ->
            new ControlSseWriter() {
              @Override
              public void opened(ControlStreamOpening opening) {
                opened.countDown();
              }

              @Override
              public void message(ControlSequencedEvent event) throws IOException {
                throw new IOException("writer-secret");
              }

              @Override
              public void keepalive() {}
            };
    var controller =
        new ControlPlaneSseController(
            runtime,
            ControlPlaneStatusServiceTest.properties(),
            sessions,
            java.time.Clock.systemUTC(),
            ControlPlaneAudit.disabled(),
            failingWriter,
            new ObjectMapper());
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.addHeader("Accept", "text/event-stream");
    request.setAttribute(ControlPlaneSecurityFilter.PRINCIPAL_ATTRIBUTE, principal);
    request.setAttribute(ControlPlaneSecurityFilter.REQUEST_ID_ATTRIBUTE, "request-failure");

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var stream =
          executor.submit(
              () -> runStream(controller, turnRef, request, new MockHttpServletResponse()));
      assertThat(opened.await(2, TimeUnit.SECONDS)).isTrue();
      var route = new MessageRoute("telegram", "10001");
      registration.observe(OutboundMessage.started("turn-1", "session-1", route));
      stream.get(2, TimeUnit.SECONDS);
    }

    assertThat(runtime.eventHub().subscriberCount()).isZero();
    assertThat(source.token().isCancellationRequested()).isFalse();
  }

  private static void runStream(
      ControlPlaneSseController controller,
      String turnRef,
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    try {
      controller.events(turnRef, request, response);
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }
}
