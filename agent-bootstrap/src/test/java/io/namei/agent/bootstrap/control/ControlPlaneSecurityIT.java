package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class ControlPlaneSecurityIT {
  @Test
  void hostileRemoteHostOriginAndCredentialNeverReachAnyControlUseCaseOrLeakMarkers()
      throws Exception {
    var sessions =
        new OperatorSessionStore(
            java.time.Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), java.time.ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            actor -> {});
    var filter =
        new ControlPlaneSecurityFilter(
            new LoopbackRequestGuard(),
            sessions,
            ControlPlaneAudit.disabled(),
            () -> "security-request",
            new ObjectMapper());
    var invoked = new AtomicBoolean();
    for (MockHttpServletRequest request : hostileRequests()) {
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, (ignored, result) -> invoked.set(true));
      assertThat(response.getStatus()).isIn(401, 403);
      assertThat(response.getContentAsString())
          .doesNotContain("attacker.example", "credential-secret", "Authorization");
      assertThat(response.getHeaderNames())
          .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("access-control-"));
    }
    assertThat(invoked).isFalse();
  }

  private static java.util.List<MockHttpServletRequest> hostileRequests() {
    var remote = request();
    remote.setRemoteAddr("192.0.2.10");
    var host = request();
    host.removeHeader("Host");
    host.addHeader("Host", "attacker.example");
    var origin = request();
    origin.addHeader("Origin", "https://attacker.example");
    var credential = request();
    credential.addHeader("Authorization", "Bearer credential-secret");
    return java.util.List.of(remote, host, origin, credential);
  }

  private static MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("GET", "/api/v1/control/status");
    request.setRemoteAddr("127.0.0.1");
    request.setScheme("http");
    request.addHeader("Host", "127.0.0.1:8080");
    return request;
  }
}
