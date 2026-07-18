package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.control.ControlStableCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoopbackRequestGuardTest {
  private final LoopbackRequestGuard guard = new LoopbackRequestGuard();

  @Test
  void acceptsOnlyLoopbackRemoteApprovedHostAndExactOptionalOrigin() {
    assertThatCode(
            () ->
                guard.validate(
                    request("GET", "/api/v1/control/status", "127.0.0.1", "127.0.0.1:8080", null)))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                guard.validate(
                    request(
                        "GET", "/api/v1/control/turns", "::1", "[::1]:8080", "http://[::1]:8080")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                guard.validate(
                    request("GET", "/api/v1/control/turns", "::1", "[::1]", "http://[::1]")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                guard.validate(
                    request(
                        "POST",
                        "/api/v1/control/session",
                        "127.0.0.2",
                        "localhost",
                        "http://localhost")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsRemoteEvenWhenForwardedClaimsLoopback() {
    MockHttpServletRequest request =
        request("GET", "/api/v1/control/status", "192.0.2.10", "127.0.0.1:8080", null);
    request.addHeader("X-Forwarded-For", "127.0.0.1");

    assertRejected(request, ControlStableCode.CONTROL_REMOTE_ACCESS_REJECTED);
  }

  @Test
  void rejectsMalformedDuplicateAndForeignHosts() {
    for (String host :
        new String[] {
          "attacker.example", "127.0.0.1@attacker", "127.0.0.1:", "127.0.0.1:65536",
          "::1", "[::1", "LOCALHOST", ""
        }) {
      assertRejected(
          request("GET", "/api/v1/control/status", "127.0.0.1", host, null),
          ControlStableCode.CONTROL_HOST_REJECTED);
    }
    MockHttpServletRequest duplicate =
        request("GET", "/api/v1/control/status", "127.0.0.1", "127.0.0.1", null);
    duplicate.addHeader("Host", "localhost");
    assertRejected(duplicate, ControlStableCode.CONTROL_HOST_REJECTED);
  }

  @Test
  void rejectsCrossOriginUnknownShapeQueryAndEveryNonEmptyBody() {
    assertRejected(
        request(
            "GET",
            "/api/v1/control/status",
            "127.0.0.1",
            "127.0.0.1:8080",
            "https://attacker.example"),
        ControlStableCode.CONTROL_ORIGIN_REJECTED);
    assertRejected(
        request("OPTIONS", "/api/v1/control/status", "127.0.0.1", "127.0.0.1", null),
        ControlStableCode.CONTROL_REQUEST_INVALID);
    assertRejected(
        request("POST", "/api/v1/control/status", "127.0.0.1", "127.0.0.1", null),
        ControlStableCode.CONTROL_REQUEST_INVALID);
    MockHttpServletRequest query =
        request("GET", "/api/v1/control/status", "127.0.0.1", "127.0.0.1", null);
    query.setQueryString("secret=value");
    assertRejected(query, ControlStableCode.CONTROL_REQUEST_INVALID);
    MockHttpServletRequest body =
        request("POST", "/api/v1/control/session", "127.0.0.1", "127.0.0.1", null);
    body.setContent("x".getBytes(StandardCharsets.UTF_8));
    assertRejected(body, ControlStableCode.CONTROL_REQUEST_INVALID);
  }

  private void assertRejected(MockHttpServletRequest request, ControlStableCode code) {
    assertThatThrownBy(() -> guard.validate(request))
        .isInstanceOfSatisfying(
            ControlRequestRejectedException.class,
            failure -> org.assertj.core.api.Assertions.assertThat(failure.code()).isEqualTo(code));
  }

  private static MockHttpServletRequest request(
      String method, String path, String remote, String host, String origin) {
    var request = new MockHttpServletRequest(method, path);
    request.setScheme("http");
    request.setRemoteAddr(remote);
    request.addHeader("Host", host);
    if (origin != null) {
      request.addHeader("Origin", origin);
    }
    return request;
  }
}
