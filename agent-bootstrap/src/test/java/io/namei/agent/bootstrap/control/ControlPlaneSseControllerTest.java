package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.control.ControlSequencedEvent;
import io.namei.agent.application.control.ControlStreamOpening;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.control.ControlEventProjection;
import io.namei.agent.kernel.control.ControlTurnState;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class ControlPlaneSseControllerTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void writesEscapedOpenedAndMessageFramesWithIndependentContinuousIds() throws Exception {
    var response = new MockHttpServletResponse();
    ControlSseWriter writer = new ServletControlSseWriterFactory(new ObjectMapper()).open(response);
    var reference = ControlPlaneStatusServiceTest.reference(1);

    writer.opened(new ControlStreamOpening(reference, ControlTurnState.ACTIVE, 3L, NOW, false));
    writer.message(
        new ControlSequencedEvent(
            1,
            ControlEventProjection.from(
                reference,
                OutboundMessage.delta(
                    "raw-turn-secret",
                    "telegram:raw-session-secret",
                    new MessageRoute("telegram", "raw-route-secret"),
                    4,
                    "回答\n片段"))));
    writer.keepalive();

    assertThat(response.getContentType()).isEqualTo("text/event-stream;charset=UTF-8");
    assertThat(response.getContentAsString())
        .startsWith("id: 0\nevent: control.stream.opened.v1\ndata: ")
        .contains(
            "\n\nid: 1\nevent: control.turn.message.v1\ndata: ", "\\n片段", "\n\n: keepalive\n\n")
        .doesNotContain("raw-turn-secret", "raw-session-secret", "raw-route-secret");
  }

  @Test
  void rejectsReplayBeforeCommittingTheEventStream() throws Exception {
    Fixture fixture = new Fixture();
    var request = fixture.request();
    request.addHeader("Last-Event-ID", "4");
    var response = new MockHttpServletResponse();

    fixture.controller.events(fixture.turnRef, request, response);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getContentAsString())
        .contains("CONTROL_EVENT_REPLAY_UNAVAILABLE", "request-sse-1");
    assertThat(fixture.runtime.eventHub().subscriberCount()).isZero();
  }

  static final class Fixture {
    final ControlPlaneRuntime runtime = ControlPlaneStatusServiceTest.runtime();
    final io.namei.agent.application.TurnCancellationSource source =
        new io.namei.agent.application.TurnCancellationSource();
    final io.namei.agent.application.control.ActiveTurnRegistration registration =
        runtime.register(
            "telegram",
            io.namei.agent.application.control.ControlCancellationHandle.from(source),
            NOW);
    final String turnRef = registration.turnRef().orElseThrow().value();
    final OperatorSessionStore sessions =
        new OperatorSessionStore(
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            java.time.Duration.ofMinutes(15),
            1,
            runtime.eventHub()::closeActor);
    final OperatorSessionPrincipal principal;
    final ControlPlaneSseController controller;

    Fixture() {
      OperatorSessionCreated created = sessions.create();
      principal = sessions.authenticate(created.accessToken()).orElseThrow();
      controller =
          new ControlPlaneSseController(
              runtime,
              ControlPlaneStatusServiceTest.properties(),
              sessions,
              java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
              ControlPlaneAudit.disabled(),
              new ServletControlSseWriterFactory(new ObjectMapper()),
              new ObjectMapper());
    }

    MockHttpServletRequest request() {
      var request =
          new MockHttpServletRequest("GET", "/api/v1/control/turns/" + turnRef + "/events");
      request.addHeader("Accept", "text/event-stream");
      request.setAttribute(ControlPlaneSecurityFilter.PRINCIPAL_ATTRIBUTE, principal);
      request.setAttribute(ControlPlaneSecurityFilter.REQUEST_ID_ATTRIBUTE, "request-sse-1");
      return request;
    }
  }
}
