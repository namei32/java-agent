package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlStableCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/control/session")
@ConditionalOnProperty(prefix = "agent.control-plane", name = "mode", havingValue = "LOOPBACK")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class ControlPlaneSessionController {
  private final OperatorSessionStore sessions;
  private final ControlPlaneAudit audit;

  public ControlPlaneSessionController(OperatorSessionStore sessions, ControlPlaneAudit audit) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  @PostMapping
  ResponseEntity<ControlSessionResponse> create(HttpServletRequest request) {
    OperatorSessionCreated created = sessions.create();
    audit.record("SESSION_CREATE", "ACCEPTED", null, requestId(request), null, null, 1, 0);
    return ResponseEntity.status(HttpStatus.CREATED).body(ControlSessionResponse.from(created));
  }

  @DeleteMapping
  ResponseEntity<Void> revoke(HttpServletRequest request) {
    OperatorSessionPrincipal principal = principal(request);
    sessions.revoke(principal.actorRef());
    audit.record(
        "SESSION_REVOKE", "ACCEPTED", null, requestId(request), principal.actorRef(), null, 1, 0);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(OperatorSessionCapacityException.class)
  ResponseEntity<ControlErrorResponse> capacity(HttpServletRequest request) {
    ControlStableCode code = ControlStableCode.CONTROL_SESSION_CAPACITY_EXCEEDED;
    String requestId = requestId(request);
    audit.record("SESSION_CREATE", "REJECTED", code, requestId, null, null, 0, 0);
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(ControlErrorResponse.of(code, requestId));
  }

  private static OperatorSessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(ControlPlaneSecurityFilter.PRINCIPAL_ATTRIBUTE);
    if (value instanceof OperatorSessionPrincipal principal) {
      return principal;
    }
    throw new IllegalStateException("控制面认证主体缺失");
  }

  private static String requestId(HttpServletRequest request) {
    Object value = request.getAttribute(ControlPlaneSecurityFilter.REQUEST_ID_ATTRIBUTE);
    if (value instanceof String requestId) {
      return requestId;
    }
    throw new IllegalStateException("控制面请求 ID 缺失");
  }
}
