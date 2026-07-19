package io.namei.agent.bootstrap.control;

import io.namei.agent.application.MemoryForgetControlService;
import io.namei.agent.application.PendingOperationControlOutcome;
import io.namei.agent.application.PendingOperationControlStatus;
import io.namei.agent.application.PendingOperationReference;
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

/**
 * Loopback-only control boundary for the single approved {@code forget_memory} recovery flow. Every
 * successful response is the three-field safe status projection; capsules and Tool arguments never
 * cross this boundary.
 */
@RestController
@RequestMapping("/api/v1/control/pending-operations")
@ConditionalOnProperty(
    prefix = "agent.capabilities.memory-forget",
    name = "mode",
    havingValue = "LOOPBACK_APPROVAL")
@ConditionalOnProperty(prefix = "agent.approval-inbox", name = "mode", havingValue = "LOOPBACK")
@ConditionalOnProperty(prefix = "agent.control-plane", name = "mode", havingValue = "LOOPBACK")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PendingOperationController {
  private final MemoryForgetControlService control;
  private final ControlPlaneAudit audit;

  public PendingOperationController(MemoryForgetControlService control, ControlPlaneAudit audit) {
    this.control = Objects.requireNonNull(control, "control");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  @GetMapping("/{operationRef}")
  ResponseEntity<?> status(@PathVariable String operationRef, HttpServletRequest request) {
    PendingOperationReference reference;
    try {
      reference = PendingOperationReference.of(operationRef);
      var result = control.status(reference);
      if (result.isEmpty()) {
        return missing(request, operationRef, "PENDING_OPERATION_STATUS");
      }
      audit(request, "PENDING_OPERATION_STATUS", "FOUND", null, operationRef, 1);
      return ResponseEntity.ok(result.orElseThrow());
    } catch (IllegalArgumentException invalid) {
      return invalid(request, operationRef, "PENDING_OPERATION_STATUS");
    } catch (RuntimeException unavailable) {
      return unavailable(request, operationRef, "PENDING_OPERATION_STATUS");
    }
  }

  @PostMapping("/{operationRef}/resume")
  ResponseEntity<?> resume(@PathVariable String operationRef, HttpServletRequest request) {
    try {
      PendingOperationReference reference = PendingOperationReference.of(operationRef);
      return afterAction(
          request, operationRef, "PENDING_OPERATION_RESUME", control.resume(reference), reference);
    } catch (IllegalArgumentException invalid) {
      return invalid(request, operationRef, "PENDING_OPERATION_RESUME");
    } catch (RuntimeException unavailable) {
      return unavailable(request, operationRef, "PENDING_OPERATION_RESUME");
    }
  }

  @PostMapping("/{operationRef}/cancel")
  ResponseEntity<?> cancel(@PathVariable String operationRef, HttpServletRequest request) {
    try {
      PendingOperationReference reference = PendingOperationReference.of(operationRef);
      return afterAction(
          request, operationRef, "PENDING_OPERATION_CANCEL", control.cancel(reference), reference);
    } catch (IllegalArgumentException invalid) {
      return invalid(request, operationRef, "PENDING_OPERATION_CANCEL");
    } catch (RuntimeException unavailable) {
      return unavailable(request, operationRef, "PENDING_OPERATION_CANCEL");
    }
  }

  private ResponseEntity<?> afterAction(
      HttpServletRequest request,
      String operationRef,
      String action,
      PendingOperationControlOutcome outcome,
      PendingOperationReference reference) {
    return switch (outcome) {
      case RESUMED, CANCELLED, ALREADY_TERMINAL ->
          successAfterAction(request, operationRef, action, outcome, reference);
      case NOT_FOUND -> missing(request, operationRef, action);
      case NOT_RESUMABLE ->
          conflict(
              request,
              operationRef,
              action,
              ControlStableCode.PENDING_RECOVERY_NOT_RESUMABLE,
              outcome);
      case UNKNOWN_REQUIRES_OPERATOR ->
          conflict(
              request,
              operationRef,
              action,
              ControlStableCode.PENDING_RECOVERY_UNKNOWN_REQUIRES_OPERATOR,
              outcome);
      case NOT_CANCELLABLE ->
          conflict(
              request,
              operationRef,
              action,
              ControlStableCode.PENDING_RECOVERY_NOT_CANCELLABLE,
              outcome);
    };
  }

  private ResponseEntity<?> successAfterAction(
      HttpServletRequest request,
      String operationRef,
      String action,
      PendingOperationControlOutcome outcome,
      PendingOperationReference reference) {
    PendingOperationControlStatus status =
        control.status(reference).orElseThrow(() -> new IllegalStateException("已完成控制操作缺少状态"));
    audit(request, action, outcome.name(), null, operationRef, 1);
    return ResponseEntity.ok(status);
  }

  private ResponseEntity<?> missing(
      HttpServletRequest request, String operationRef, String action) {
    audit(
        request,
        action,
        "NOT_FOUND",
        ControlStableCode.PENDING_RECOVERY_NOT_FOUND,
        operationRef,
        0);
    return error(HttpStatus.NOT_FOUND, ControlStableCode.PENDING_RECOVERY_NOT_FOUND, request);
  }

  private ResponseEntity<?> invalid(
      HttpServletRequest request, String operationRef, String action) {
    audit(
        request,
        action,
        "REJECTED",
        ControlStableCode.PENDING_RECOVERY_REQUEST_INVALID,
        operationRef,
        0);
    return error(
        HttpStatus.BAD_REQUEST, ControlStableCode.PENDING_RECOVERY_REQUEST_INVALID, request);
  }

  private ResponseEntity<?> conflict(
      HttpServletRequest request,
      String operationRef,
      String action,
      ControlStableCode code,
      PendingOperationControlOutcome outcome) {
    audit(request, action, outcome.name(), code, operationRef, 0);
    return error(HttpStatus.CONFLICT, code, request);
  }

  private ResponseEntity<?> unavailable(
      HttpServletRequest request, String operationRef, String action) {
    audit(
        request,
        action,
        "UNAVAILABLE",
        ControlStableCode.PENDING_RECOVERY_UNAVAILABLE,
        operationRef,
        0);
    return error(
        HttpStatus.SERVICE_UNAVAILABLE, ControlStableCode.PENDING_RECOVERY_UNAVAILABLE, request);
  }

  private void audit(
      HttpServletRequest request,
      String action,
      String result,
      ControlStableCode code,
      String operationRef,
      long count) {
    audit.record(
        action,
        result,
        code,
        requestId(request),
        principal(request).actorRef(),
        operationRef,
        count,
        0);
  }

  private static ResponseEntity<ControlErrorResponse> error(
      HttpStatus status, ControlStableCode code, HttpServletRequest request) {
    return ResponseEntity.status(status).body(ControlErrorResponse.of(code, requestId(request)));
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
