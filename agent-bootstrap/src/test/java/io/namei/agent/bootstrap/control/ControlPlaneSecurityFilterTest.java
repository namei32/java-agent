package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

class ControlPlaneSecurityFilterTest {
  private OperatorSessionStore sessions;
  private ControlPlaneSecurityFilter filter;
  private OperatorSessionStoreTest.MutableClock clock;

  @BeforeEach
  void setUp() {
    clock = new OperatorSessionStoreTest.MutableClock(Instant.parse("2026-07-18T00:00:00Z"));
    sessions =
        new OperatorSessionStore(
            clock,
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(1),
            2,
            actor -> {});
    filter =
        new ControlPlaneSecurityFilter(
            new LoopbackRequestGuard(),
            sessions,
            ControlPlaneAudit.disabled(),
            () -> "server-request-1",
            new ObjectMapper());
  }

  @Test
  void sessionCreationPathNeedsLoopbackButNotAnExistingBearer() throws Exception {
    MockHttpServletRequest request = request("POST", "/api/v1/control/session");
    MockHttpServletResponse response = new MockHttpServletResponse();
    var invoked = new AtomicBoolean();

    filter.doFilter(request, response, (ignored, result) -> invoked.set(true));

    assertThat(invoked).isTrue();
    assertSecurityHeaders(response);
  }

  @Test
  void createsAndRevokesOneTimeHttpSessionWithExactSafeEnvelope() throws Exception {
    MockMvc mvc =
        standaloneSetup(new ControlPlaneSessionController(sessions, ControlPlaneAudit.disabled()))
            .addFilters(filter)
            .build();

    String response =
        mvc.perform(
                post("/api/v1/control/session")
                    .with(
                        request -> {
                          request.setRemoteAddr("127.0.0.1");
                          request.setScheme("http");
                          request.addHeader("Host", "127.0.0.1:8080");
                          return request;
                        }))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.schemaVersion").value(1))
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresAt").value("2026-07-18T00:01:00Z"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = new ObjectMapper().readTree(response).get("accessToken").asText();

    mvc.perform(
            delete("/api/v1/control/session")
                .with(
                    request -> {
                      request.setRemoteAddr("127.0.0.1");
                      request.setScheme("http");
                      request.addHeader("Host", "127.0.0.1:8080");
                      request.addHeader("Authorization", "Bearer " + token);
                      return request;
                    }))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    mvc.perform(
            delete("/api/v1/control/session")
                .with(
                    request -> {
                      request.setRemoteAddr("127.0.0.1");
                      request.setScheme("http");
                      request.addHeader("Host", "127.0.0.1:8080");
                      request.addHeader("Authorization", "Bearer " + token);
                      return request;
                    }))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CONTROL_AUTHENTICATION_REQUIRED"));
  }

  @Test
  void sessionCapacityReturnsTheStableTooManyRequestsEnvelope() throws Exception {
    MockMvc mvc =
        standaloneSetup(new ControlPlaneSessionController(sessions, ControlPlaneAudit.disabled()))
            .addFilters(filter)
            .build();
    sessions.create();
    sessions.create();

    mvc.perform(
            post("/api/v1/control/session")
                .with(
                    request -> {
                      request.setRemoteAddr("127.0.0.1");
                      request.setScheme("http");
                      request.addHeader("Host", "127.0.0.1:8080");
                      return request;
                    }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("CONTROL_SESSION_CAPACITY_EXCEEDED"))
        .andExpect(jsonPath("$.retryable").value(false))
        .andExpect(jsonPath("$.requestId").value("server-request-1"));
  }

  @Test
  void validBearerPublishesOnlyPrincipalAndOverridesCallerRequestId() throws Exception {
    OperatorSessionCreated created = sessions.create();
    MockHttpServletRequest request = request("GET", "/api/v1/control/status");
    request.addHeader("Authorization", "Bearer " + created.accessToken());
    request.addHeader("X-Request-Id", "caller-controlled");
    MockHttpServletResponse response = new MockHttpServletResponse();
    var invoked = new AtomicBoolean();

    filter.doFilter(request, response, (secured, result) -> invoked.set(true));

    assertThat(invoked).isTrue();
    assertThat(request.getAttribute(ControlPlaneSecurityFilter.PRINCIPAL_ATTRIBUTE))
        .isInstanceOf(OperatorSessionPrincipal.class);
    assertThat(request.getAttribute(ControlPlaneSecurityFilter.REQUEST_ID_ATTRIBUTE))
        .isEqualTo("server-request-1");
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("server-request-1");
    assertSecurityHeaders(response);
  }

  @Test
  void everyInvalidCredentialReturnsOneUniformSafeUnauthorizedResponse() throws Exception {
    OperatorSessionCreated expired = sessions.create();
    clock.advance(Duration.ofMinutes(1));

    for (String authorization :
        new String[] {
          "",
          "Basic secret",
          "bearer " + "A".repeat(43),
          "Bearer malformed",
          "Bearer " + expired.accessToken(),
          "Bearer " + "A".repeat(43)
        }) {
      MockHttpServletRequest request = request("GET", "/api/v1/control/status");
      if (!authorization.isEmpty()) {
        request.addHeader("Authorization", authorization);
      }
      MockHttpServletResponse response = new MockHttpServletResponse();
      var invoked = new AtomicBoolean();

      filter.doFilter(request, response, (ignored, result) -> invoked.set(true));

      assertThat(invoked).isFalse();
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getContentAsString())
          .contains("CONTROL_AUTHENTICATION_REQUIRED", "server-request-1")
          .doesNotContain("malformed", expired.accessToken(), "Basic secret");
      assertSecurityHeaders(response);
    }
  }

  @Test
  void guardRejectsBeforeAuthenticationAndNeverAddsCorsHeaders() throws Exception {
    MockHttpServletRequest request = request("GET", "/api/v1/control/status");
    request.setRemoteAddr("192.0.2.10");
    request.addHeader("X-Forwarded-For", "127.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (ignored, result) -> {});

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("CONTROL_REMOTE_ACCESS_REJECTED");
    assertThat(response.getHeaderNames())
        .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("access-control-"));
    assertSecurityHeaders(response);
  }

  private static MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr("127.0.0.1");
    request.setScheme("http");
    request.addHeader("Host", "127.0.0.1:8080");
    return request;
  }

  private static void assertSecurityHeaders(MockHttpServletResponse response) {
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("server-request-1");
  }
}
