package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlCancellationOutcome;
import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlStableCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/control")
@ConditionalOnProperty(prefix = "agent.control-plane", name = "mode", havingValue = "LOOPBACK")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class ControlPlaneController {
  private final ControlPlaneStatusService control;

  public ControlPlaneController(ControlPlaneStatusService control) {
    this.control = Objects.requireNonNull(control, "control");
  }

  @GetMapping("/status")
  ControlStatusResponse status() {
    return control.status();
  }

  @GetMapping("/turns")
  ControlTurnsResponse turns() {
    return control.turns();
  }

  @PostMapping("/turns/{turnRef}/cancel")
  ResponseEntity<?> cancel(@PathVariable String turnRef, HttpServletRequest request) {
    ControlCancellationOutcome outcome;
    try {
      outcome = control.cancel(turnRef);
    } catch (IllegalArgumentException invalidReference) {
      return ResponseEntity.badRequest()
          .body(
              ControlErrorResponse.of(
                  ControlStableCode.CONTROL_REQUEST_INVALID, requestId(request)));
    }
    if (outcome.result() == ControlCancelResult.NOT_FOUND) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              ControlErrorResponse.of(
                  ControlStableCode.CONTROL_TURN_NOT_FOUND, requestId(request)));
    }
    HttpStatus status =
        outcome.result() == ControlCancelResult.CANCELLATION_REQUESTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    return ResponseEntity.status(status).body(ControlCancelResponse.from(turnRef, outcome));
  }

  private static String requestId(HttpServletRequest request) {
    Object value = request.getAttribute(ControlPlaneSecurityFilter.REQUEST_ID_ATTRIBUTE);
    if (value instanceof String requestId) {
      return requestId;
    }
    throw new IllegalStateException("控制面请求 ID 缺失");
  }
}
