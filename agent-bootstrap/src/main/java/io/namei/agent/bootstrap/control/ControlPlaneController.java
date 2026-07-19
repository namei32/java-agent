package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlCancellationOutcome;
import io.namei.agent.kernel.control.ControlCancelResult;
import io.namei.agent.kernel.control.ControlStableCode;
import io.namei.agent.kernel.control.HistoryDetailReadRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
  private final ControlPlaneAudit audit;
  private final ControlHistoryDetailService historyDetail;

  public ControlPlaneController(
      ControlPlaneStatusService control,
      ControlPlaneAudit audit,
      ControlHistoryDetailService historyDetail) {
    this.control = Objects.requireNonNull(control, "control");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.historyDetail = Objects.requireNonNull(historyDetail, "historyDetail");
  }

  @GetMapping("/status")
  ControlStatusResponse status() {
    return control.status();
  }

  @GetMapping("/turns")
  ControlTurnsResponse turns() {
    return control.turns();
  }

  @GetMapping("/index")
  ResponseEntity<?> index(HttpServletRequest request) {
    try {
      IndexQuery query = IndexQuery.parse(request);
      return ResponseEntity.ok(
          control.index(query.pageSize(), query.cursor(), principal(request).actorRef()));
    } catch (IllegalArgumentException invalid) {
      return ResponseEntity.badRequest()
          .body(
              ControlErrorResponse.of(
                  ControlStableCode.CONTROL_REQUEST_INVALID, requestId(request)));
    }
  }

  @GetMapping("/history")
  ResponseEntity<?> history(HttpServletRequest request) {
    try {
      HistoryQuery query = HistoryQuery.parse(request);
      return ResponseEntity.ok(
          control.history(query.pageSize(), query.cursor(), principal(request).actorRef()));
    } catch (IllegalArgumentException invalid) {
      return ResponseEntity.badRequest()
          .body(
              ControlErrorResponse.of(
                  ControlStableCode.CONTROL_REQUEST_INVALID, requestId(request)));
    }
  }

  @GetMapping("/history/detail")
  ResponseEntity<?> historyDetail(HttpServletRequest request) {
    try {
      DetailQuery query = DetailQuery.parse(request);
      ControlHistoryDetailResponse response =
          historyDetail.detail(
              query.pageSize(), query.reference(), query.cursor(), principal(request).actorRef());
      auditHistoryDetail(
          request,
          response.state(),
          code(response.code()),
          response.items().size(),
          response.detailRef().isEmpty() ? query.referenceOrCursor() : response.detailRef());
      return ResponseEntity.ok(response);
    } catch (ControlHistoryDetailNotFoundException notFound) {
      auditHistoryDetail(
          request,
          "NOT_FOUND",
          ControlStableCode.CONTROL_HISTORY_NOT_FOUND,
          0,
          DetailQuery.referenceOrCursor(request));
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              ControlErrorResponse.of(
                  ControlStableCode.CONTROL_HISTORY_NOT_FOUND, requestId(request)));
    } catch (IllegalArgumentException invalid) {
      auditHistoryDetail(request, "REJECTED", ControlStableCode.CONTROL_REQUEST_INVALID, 0, "");
      return ResponseEntity.badRequest()
          .body(
              ControlErrorResponse.of(
                  ControlStableCode.CONTROL_REQUEST_INVALID, requestId(request)));
    }
  }

  @PostMapping("/turns/{turnRef}/cancel")
  ResponseEntity<?> cancel(@PathVariable String turnRef, HttpServletRequest request) {
    ControlCancellationOutcome outcome;
    try {
      outcome = control.cancel(turnRef);
    } catch (IllegalArgumentException invalidReference) {
      auditCancel(request, turnRef, "REJECTED", ControlStableCode.CONTROL_REQUEST_INVALID, 0);
      return ResponseEntity.badRequest()
          .body(
              ControlErrorResponse.of(
                  ControlStableCode.CONTROL_REQUEST_INVALID, requestId(request)));
    }
    if (outcome.result() == ControlCancelResult.NOT_FOUND) {
      auditCancel(
          request, turnRef, outcome.result().name(), ControlStableCode.CONTROL_TURN_NOT_FOUND, 0);
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              ControlErrorResponse.of(
                  ControlStableCode.CONTROL_TURN_NOT_FOUND, requestId(request)));
    }
    HttpStatus status =
        outcome.result() == ControlCancelResult.CANCELLATION_REQUESTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    auditCancel(request, turnRef, outcome.result().name(), null, 1);
    return ResponseEntity.status(status).body(ControlCancelResponse.from(turnRef, outcome));
  }

  private void auditCancel(
      HttpServletRequest request,
      String turnRef,
      String result,
      ControlStableCode code,
      long count) {
    audit.record(
        "TURN_CANCEL",
        result,
        code,
        requestId(request),
        principal(request).actorRef(),
        turnRef,
        count,
        0);
  }

  private void auditHistoryDetail(
      HttpServletRequest request,
      String result,
      ControlStableCode code,
      long count,
      String opaqueDetailReference) {
    audit.record(
        "HISTORY_DETAIL",
        result,
        code,
        requestId(request),
        principal(request).actorRef(),
        opaqueDetailReference,
        count,
        0);
  }

  private static ControlStableCode code(String value) {
    return value == null || value.isEmpty() ? null : ControlStableCode.parse(value);
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

  private record IndexQuery(int pageSize, String cursor) {
    private static final Set<String> ALLOWED_PARAMETERS = Set.of("pageSize", "cursor");

    static IndexQuery parse(HttpServletRequest request) {
      Map<String, String[]> parameters = request.getParameterMap();
      if (!ALLOWED_PARAMETERS.containsAll(parameters.keySet())) {
        throw new IllegalArgumentException("控制索引包含未批准的查询参数");
      }
      String pageSize = one(parameters, "pageSize");
      String cursor = one(parameters, "cursor");
      if (pageSize == null) {
        return new IndexQuery(
            ControlPlaneStatusService.defaultIndexPageSize(), cursor == null ? "" : cursor);
      }
      try {
        return new IndexQuery(Integer.parseInt(pageSize), cursor == null ? "" : cursor);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException("控制索引分页大小格式无效", invalid);
      }
    }

    private static String one(Map<String, String[]> parameters, String name) {
      String[] values = parameters.get(name);
      if (values == null) {
        return null;
      }
      if (values.length != 1 || values[0] == null || values[0].isEmpty()) {
        throw new IllegalArgumentException("控制索引查询参数无效");
      }
      return values[0];
    }
  }

  private record HistoryQuery(int pageSize, String cursor) {
    private static final Set<String> ALLOWED_PARAMETERS = Set.of("pageSize", "cursor");

    static HistoryQuery parse(HttpServletRequest request) {
      Map<String, String[]> parameters = request.getParameterMap();
      if (!ALLOWED_PARAMETERS.containsAll(parameters.keySet())) {
        throw new IllegalArgumentException("控制历史包含未批准的查询参数");
      }
      String pageSize = one(parameters, "pageSize");
      String cursor = one(parameters, "cursor");
      if (pageSize == null) {
        return new HistoryQuery(
            ControlPlaneStatusService.defaultHistoryPageSize(), cursor == null ? "" : cursor);
      }
      try {
        return new HistoryQuery(Integer.parseInt(pageSize), cursor == null ? "" : cursor);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException("控制历史分页大小格式无效", invalid);
      }
    }

    private static String one(Map<String, String[]> parameters, String name) {
      String[] values = parameters.get(name);
      if (values == null) {
        return null;
      }
      if (values.length != 1 || values[0] == null || values[0].isEmpty()) {
        throw new IllegalArgumentException("控制历史查询参数无效");
      }
      return values[0];
    }
  }

  private record DetailQuery(int pageSize, String reference, String cursor) {
    private static final Set<String> ALLOWED_PARAMETERS = Set.of("pageSize", "ref", "cursor");

    static DetailQuery parse(HttpServletRequest request) {
      Map<String, String[]> parameters = request.getParameterMap();
      if (!ALLOWED_PARAMETERS.containsAll(parameters.keySet())) {
        throw new IllegalArgumentException("控制历史详情包含未批准的查询参数");
      }
      String pageSize = one(parameters, "pageSize");
      String reference = one(parameters, "ref");
      String cursor = one(parameters, "cursor");
      if (pageSize == null) {
        return new DetailQuery(
            HistoryDetailReadRequest.DEFAULT_PAGE_SIZE,
            reference == null ? "" : reference,
            cursor == null ? "" : cursor);
      }
      try {
        return new DetailQuery(
            Integer.parseInt(pageSize),
            reference == null ? "" : reference,
            cursor == null ? "" : cursor);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException("控制历史详情分页大小格式无效", invalid);
      }
    }

    static String referenceOrCursor(HttpServletRequest request) {
      Map<String, String[]> parameters = request.getParameterMap();
      String reference = oneIfExactlyOne(parameters.get("ref"));
      if (reference != null) {
        return reference;
      }
      String cursor = oneIfExactlyOne(parameters.get("cursor"));
      return cursor == null ? "" : cursor;
    }

    private static String one(Map<String, String[]> parameters, String name) {
      String[] values = parameters.get(name);
      if (values == null) {
        return null;
      }
      if (values.length != 1 || values[0] == null || values[0].isEmpty()) {
        throw new IllegalArgumentException("控制历史详情查询参数无效");
      }
      return values[0];
    }

    private static String oneIfExactlyOne(String[] values) {
      return values != null && values.length == 1 && values[0] != null && !values[0].isEmpty()
          ? values[0]
          : null;
    }

    String referenceOrCursor() {
      return reference.isEmpty() ? cursor : reference;
    }
  }
}
