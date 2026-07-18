package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ControlSequencedEvent;
import io.namei.agent.application.control.ControlSubscription;
import io.namei.agent.application.control.ControlSubscriptionCloseReason;
import io.namei.agent.application.control.ControlSubscriptionException;
import io.namei.agent.kernel.control.ControlStableCode;
import io.namei.agent.kernel.control.ControlTurnRef;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/control/turns")
@ConditionalOnProperty(prefix = "agent.control-plane", name = "mode", havingValue = "LOOPBACK")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class ControlPlaneSseController {
  private final ControlPlaneRuntime runtime;
  private final ControlPlaneProperties properties;
  private final OperatorSessionStore sessions;
  private final Clock clock;
  private final ControlPlaneAudit audit;
  private final ControlSseWriterFactory writers;
  private final ObjectMapper json;
  private final ControlStreamTracker streams;

  public ControlPlaneSseController(
      ControlPlaneRuntime runtime,
      ControlPlaneProperties properties,
      OperatorSessionStore sessions,
      Clock clock,
      ControlPlaneAudit audit,
      ControlSseWriterFactory writers,
      ObjectMapper json,
      ControlStreamTracker streams) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.writers = Objects.requireNonNull(writers, "writers");
    this.json = Objects.requireNonNull(json, "json");
    this.streams = Objects.requireNonNull(streams, "streams");
  }

  @GetMapping("/{turnRef}/events")
  void events(
      @PathVariable String turnRef, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String requestId = requestId(request);
    OperatorSessionPrincipal principal = principal(request);
    if (!acceptsEventStream(request)) {
      writeError(response, 400, ControlStableCode.CONTROL_REQUEST_INVALID, requestId);
      return;
    }
    if (request.getHeaders("Last-Event-ID").hasMoreElements()) {
      writeError(response, 409, ControlStableCode.CONTROL_EVENT_REPLAY_UNAVAILABLE, requestId);
      return;
    }

    var streamLease = streams.open();
    if (streamLease.isEmpty()) {
      writeError(response, 503, ControlStableCode.CONTROL_SHUTTING_DOWN, requestId);
      return;
    }
    try (var ignored = streamLease.orElseThrow()) {
      ControlSubscription subscription;
      try {
        subscription =
            runtime.eventHub().subscribe(ControlTurnRef.parse(turnRef), principal.actorRef());
      } catch (IllegalArgumentException invalidReference) {
        writeError(response, 400, ControlStableCode.CONTROL_REQUEST_INVALID, requestId);
        return;
      } catch (ControlSubscriptionException rejected) {
        writeSubscriptionError(response, rejected.reason(), requestId);
        return;
      }

      audit.record("STREAM_OPEN", "ACCEPTED", null, requestId, principal.actorRef(), turnRef, 1, 0);
      try (subscription) {
        ControlSseWriter writer = writers.open(response);
        writer.opened(subscription.opening());
        stream(subscription, writer, principal);
      } catch (IOException writerFailure) {
        // 连接已经建立，I/O 失败只关闭本订阅；不记录异常正文也不改写 JSON。
      } finally {
        ControlSubscriptionCloseReason closeReason =
            subscription.closeReason().orElse(ControlSubscriptionCloseReason.CLIENT_DISCONNECTED);
        audit.record(
            "STREAM_CLOSE",
            closeReason.name(),
            closeCode(closeReason),
            requestId,
            principal.actorRef(),
            turnRef,
            1,
            0);
      }
    }
  }

  private static ControlStableCode closeCode(ControlSubscriptionCloseReason reason) {
    return switch (reason) {
      case SLOW_CONSUMER -> ControlStableCode.CONTROL_SLOW_CONSUMER;
      case LIFETIME_EXCEEDED -> ControlStableCode.CONTROL_STREAM_LIFETIME_EXCEEDED;
      case SOURCE_ENDED -> ControlStableCode.CONTROL_SOURCE_ENDED;
      case SHUTDOWN -> ControlStableCode.CONTROL_SHUTTING_DOWN;
      case CLIENT_DISCONNECTED, SESSION_REVOKED, TERMINAL -> null;
    };
  }

  private void stream(
      ControlSubscription subscription, ControlSseWriter writer, OperatorSessionPrincipal principal)
      throws IOException {
    while (true) {
      Duration wait = sessionBoundedWait(principal.expiresAt());
      if (wait.isZero()) {
        sessions.revoke(principal.actorRef());
        return;
      }
      var event = subscription.poll(wait);
      if (event.isPresent()) {
        ControlSequencedEvent next = event.orElseThrow();
        writer.message(next);
        if (next.projection().type().isTerminal()) {
          return;
        }
      } else if (subscription.isClosed()) {
        return;
      } else if (!clock.instant().isBefore(principal.expiresAt())) {
        sessions.revoke(principal.actorRef());
        return;
      } else {
        writer.keepalive();
      }
    }
  }

  private Duration sessionBoundedWait(Instant expiresAt) {
    Duration remaining = Duration.between(clock.instant(), expiresAt);
    if (remaining.isZero() || remaining.isNegative()) {
      return Duration.ZERO;
    }
    return remaining.compareTo(properties.heartbeatInterval()) < 0
        ? remaining
        : properties.heartbeatInterval();
  }

  private void writeSubscriptionError(
      HttpServletResponse response, ControlSubscriptionException.Reason reason, String requestId)
      throws IOException {
    switch (reason) {
      case TURN_NOT_FOUND ->
          writeError(response, 404, ControlStableCode.CONTROL_TURN_NOT_FOUND, requestId);
      case ALREADY_TERMINAL ->
          writeError(response, 409, ControlStableCode.CONTROL_TURN_ALREADY_TERMINAL, requestId);
      case CAPACITY_EXCEEDED ->
          writeError(
              response, 429, ControlStableCode.CONTROL_SUBSCRIBER_CAPACITY_EXCEEDED, requestId);
      case SHUTTING_DOWN ->
          writeError(response, 503, ControlStableCode.CONTROL_SHUTTING_DOWN, requestId);
    }
  }

  private void writeError(
      HttpServletResponse response, int status, ControlStableCode code, String requestId)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    json.writeValue(response.getOutputStream(), ControlErrorResponse.of(code, requestId));
  }

  private static boolean acceptsEventStream(HttpServletRequest request) {
    var values = Collections.list(request.getHeaders("Accept"));
    if (values.isEmpty()) {
      return false;
    }
    try {
      return values.stream()
          .flatMap(value -> MediaType.parseMediaTypes(value).stream())
          .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    } catch (IllegalArgumentException malformed) {
      return false;
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
