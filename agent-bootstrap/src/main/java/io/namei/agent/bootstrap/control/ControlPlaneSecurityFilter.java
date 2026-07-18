package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlStableCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ControlPlaneSecurityFilter extends OncePerRequestFilter {
  public static final String PRINCIPAL_ATTRIBUTE =
      ControlPlaneSecurityFilter.class.getName() + ".principal";
  public static final String REQUEST_ID_ATTRIBUTE =
      ControlPlaneSecurityFilter.class.getName() + ".requestId";
  private static final String BASE = "/api/v1/control";
  private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

  private final LoopbackRequestGuard guard;
  private final OperatorSessionStore sessions;
  private final ControlPlaneAudit audit;
  private final ControlRequestIdGenerator requestIds;
  private final ObjectMapper json;

  public ControlPlaneSecurityFilter(
      LoopbackRequestGuard guard,
      OperatorSessionStore sessions,
      ControlPlaneAudit audit,
      ControlRequestIdGenerator requestIds,
      ObjectMapper json) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(BASE);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    securityHeaders(response);
    String requestId = requestIds.next();
    if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
      requestId = java.util.UUID.randomUUID().toString();
    }
    response.setHeader("X-Request-Id", requestId);
    request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
    OperatorSessionPrincipal principal = null;
    try {
      guard.validate(request);
      if (!isSessionCreation(request)) {
        principal = authenticate(request);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
      }
      audit.record(
          "CONTROL_REQUEST",
          "ACCEPTED",
          null,
          requestId,
          principal == null ? null : principal.actorRef(),
          null,
          0,
          0);
      chain.doFilter(request, response);
    } catch (ControlRequestRejectedException rejected) {
      audit.record(
          "CONTROL_REQUEST",
          "REJECTED",
          rejected.code(),
          requestId,
          principal == null ? null : principal.actorRef(),
          null,
          0,
          0);
      writeError(response, rejected.httpStatus(), rejected.code(), requestId);
    }
  }

  private OperatorSessionPrincipal authenticate(HttpServletRequest request) {
    List<String> headers = headers(request, "Authorization");
    if (headers.size() != 1) {
      throw authenticationRequired();
    }
    String value = headers.getFirst();
    if (!value.startsWith("Bearer ") || value.length() <= "Bearer ".length()) {
      throw authenticationRequired();
    }
    return sessions
        .authenticate(value.substring("Bearer ".length()))
        .orElseThrow(ControlPlaneSecurityFilter::authenticationRequired);
  }

  private void writeError(
      HttpServletResponse response, int status, ControlStableCode code, String requestId)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    json.writeValue(response.getWriter(), ControlErrorResponse.of(code, requestId));
  }

  private static boolean isSessionCreation(HttpServletRequest request) {
    return "POST".equals(request.getMethod())
        && "/api/v1/control/session".equals(request.getRequestURI());
  }

  private static ControlRequestRejectedException authenticationRequired() {
    return new ControlRequestRejectedException(
        ControlStableCode.CONTROL_AUTHENTICATION_REQUIRED, 401);
  }

  private static List<String> headers(HttpServletRequest request, String name) {
    var values = request.getHeaders(name);
    return values == null ? List.of() : new ArrayList<>(Collections.list(values));
  }

  private static void securityHeaders(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
  }
}
