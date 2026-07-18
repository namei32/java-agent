package io.namei.agent.bootstrap.control;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SafeLoggingControlPlaneAuditSink implements ControlPlaneAuditSink {
  private static final Logger logger =
      LoggerFactory.getLogger(SafeLoggingControlPlaneAuditSink.class);

  @Override
  public void accept(ControlAuditEvent event) {
    Objects.requireNonNull(event, "event");
    logger
        .atInfo()
        .addKeyValue("observedAt", event.observedAt())
        .addKeyValue("requestId", event.requestId())
        .addKeyValue("action", event.action())
        .addKeyValue("result", event.result())
        .addKeyValue("code", event.code())
        .addKeyValue("actorHash", event.actorHash())
        .addKeyValue("turnHash", event.turnHash())
        .addKeyValue("count", event.count())
        .addKeyValue("durationMs", event.durationMillis())
        .log("control audit event");
  }
}
