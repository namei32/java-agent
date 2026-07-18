package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Tag("failure")
class ControlPlaneSecurityFailureTest {
  @Test
  void auditFailureCannotChangeAnAuthenticatedRequestOrExposeMarkers() throws Exception {
    var clock = new OperatorSessionStoreTest.MutableClock(Instant.parse("2026-07-18T00:00:00Z"));
    var sessions =
        new OperatorSessionStore(
            clock,
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(1),
            1,
            actor -> {});
    OperatorSessionCreated created = sessions.create();
    var audit =
        new ControlPlaneAudit(
            clock,
            event -> {
              throw new IllegalStateException("audit-marker-secret");
            });
    var filter =
        new ControlPlaneSecurityFilter(
            new LoopbackRequestGuard(),
            sessions,
            audit,
            () -> "server-request-failure",
            new ObjectMapper());
    var request = new MockHttpServletRequest("GET", "/api/v1/control/status");
    request.setRemoteAddr("127.0.0.1");
    request.setScheme("http");
    request.addHeader("Host", "localhost:8080");
    request.addHeader("Authorization", "Bearer " + created.accessToken());
    var response = new MockHttpServletResponse();
    var invoked = new AtomicBoolean();

    filter.doFilter(request, response, (ignored, result) -> invoked.set(true));

    assertThat(invoked).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString())
        .doesNotContain("audit-marker-secret", created.accessToken(), "Authorization");
  }
}
