package io.namei.agent.bootstrap.control;

import io.namei.agent.adapter.sqlite.ApprovalInboxRepositoryException;
import io.namei.agent.application.ApprovalInboxResolution;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/control/approvals")
@ConditionalOnProperty(prefix = "agent.approval-inbox", name = "mode", havingValue = "LOOPBACK")
@ConditionalOnProperty(prefix = "agent.control-plane", name = "mode", havingValue = "LOOPBACK")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class ApprovalInboxController {
  private static final int MAX_DECISION_BODY_BYTES = 128;
  private final ApprovalInboxControlService inbox;
  private final ControlPlaneAudit audit;
  private final ObjectMapper json;

  public ApprovalInboxController(
      ApprovalInboxControlService inbox, ControlPlaneAudit audit, ObjectMapper json) {
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
  }

  @GetMapping
  ResponseEntity<?> list(HttpServletRequest request) {
    try {
      return ResponseEntity.ok(inbox.list());
    } catch (RuntimeException unavailable) {
      audit(request, "APPROVAL_LIST", "UNAVAILABLE", null, 0);
      return error(HttpStatus.SERVICE_UNAVAILABLE, "APPROVAL_INBOX_UNAVAILABLE", true, request);
    }
  }

  @PostMapping(path = "/{approvalRef}/decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> decide(@PathVariable String approvalRef, HttpServletRequest request) {
    ApprovalInboxDecisionRequest decision;
    try {
      decision = ApprovalInboxDecisionRequest.parse(readDecisionBody(request), json);
    } catch (IllegalArgumentException | IOException invalid) {
      audit(request, "APPROVAL_DECISION", "REJECTED", approvalRef, 0);
      return error(HttpStatus.BAD_REQUEST, "APPROVAL_REQUEST_INVALID", false, request);
    }
    ApprovalInboxResolution result;
    try {
      result = inbox.decide(approvalRef, decision.decision(), principal(request).actorRef());
    } catch (IllegalArgumentException invalid) {
      audit(request, "APPROVAL_DECISION", "REJECTED", approvalRef, 0);
      return error(HttpStatus.BAD_REQUEST, "APPROVAL_REQUEST_INVALID", false, request);
    } catch (ApprovalInboxRepositoryException unavailable) {
      audit(request, "APPROVAL_DECISION", "UNAVAILABLE", approvalRef, 0);
      return error(HttpStatus.SERVICE_UNAVAILABLE, "APPROVAL_INBOX_UNAVAILABLE", true, request);
    } catch (RuntimeException unavailable) {
      audit(request, "APPROVAL_DECISION", "UNAVAILABLE", approvalRef, 0);
      return error(HttpStatus.SERVICE_UNAVAILABLE, "APPROVAL_INBOX_UNAVAILABLE", true, request);
    }
    return response(request, approvalRef, result);
  }

  private ResponseEntity<?> response(
      HttpServletRequest request, String approvalRef, ApprovalInboxResolution result) {
    return switch (result.status()) {
      case RESOLVED -> {
        audit(request, "APPROVAL_DECISION", "RESOLVED", approvalRef, 1);
        yield ResponseEntity.ok(ApprovalInboxItemResponse.from(result.entry().orElseThrow()));
      }
      case NOT_FOUND -> {
        audit(request, "APPROVAL_DECISION", "NOT_FOUND", approvalRef, 0);
        yield error(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", false, request);
      }
      case ALREADY_RESOLVED -> {
        audit(request, "APPROVAL_DECISION", "ALREADY_RESOLVED", approvalRef, 0);
        yield error(HttpStatus.CONFLICT, "APPROVAL_ALREADY_RESOLVED", false, request);
      }
      case EXPIRED -> {
        audit(request, "APPROVAL_DECISION", "EXPIRED", approvalRef, 0);
        yield error(HttpStatus.CONFLICT, "APPROVAL_EXPIRED", false, request);
      }
    };
  }

  private void audit(
      HttpServletRequest request, String action, String result, String approvalRef, long count) {
    audit.record(
        action,
        result,
        null,
        requestId(request),
        principal(request).actorRef(),
        approvalRef,
        count,
        0);
  }

  private static ResponseEntity<ApprovalInboxErrorResponse> error(
      HttpStatus status, String code, boolean retryable, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .body(ApprovalInboxErrorResponse.of(code, retryable, requestId(request)));
  }

  private static byte[] readDecisionBody(HttpServletRequest request) throws IOException {
    long declaredLength = request.getContentLengthLong();
    if (declaredLength < 0 || declaredLength > MAX_DECISION_BODY_BYTES) {
      throw new IllegalArgumentException("审批请求正文超出上限");
    }
    try (var stream = request.getInputStream()) {
      byte[] body = stream.readNBytes(MAX_DECISION_BODY_BYTES + 1);
      if (body.length > MAX_DECISION_BODY_BYTES || stream.read() != -1) {
        throw new IllegalArgumentException("审批请求正文超出上限");
      }
      return body;
    }
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
