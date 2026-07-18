package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.namei.agent.kernel.control.ControlStableCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ControlPlaneAuditTest {
  @Test
  void productionSinkLogsOnlyTheSafeContractProjection() {
    Logger logger = (Logger) LoggerFactory.getLogger(SafeLoggingControlPlaneAuditSink.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var audit =
          new ControlPlaneAudit(
              Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC),
              new SafeLoggingControlPlaneAuditSink());

      audit.record(
          "TURN_CANCEL",
          "CANCELLATION_REQUESTED",
          null,
          "request-1",
          "raw-actor-secret",
          "raw-turn-secret",
          1,
          2);

      String log =
          appender.list.stream()
              .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
              .reduce("", String::concat);
      assertThat(log)
          .contains(
              "control audit event",
              "action=\"TURN_CANCEL\"",
              "result=\"CANCELLATION_REQUESTED\"",
              "requestId=\"request-1\"",
              "count=\"1\"",
              "durationMs=\"2\"")
          .doesNotContain(
              "raw-actor-secret", "raw-turn-secret", "Bearer", "Authorization", "message-body");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void hashesSensitiveReferencesAndNeverLetsSinkFailureEscape() {
    String actor = "raw-actor-secret";
    String turn = "raw-turn-secret";
    var events = new ArrayList<ControlAuditEvent>();
    var audit =
        new ControlPlaneAudit(
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC), events::add);

    audit.record(
        "AUTHENTICATE",
        "REJECTED",
        ControlStableCode.CONTROL_AUTHENTICATION_REQUIRED,
        "request-1",
        actor,
        turn,
        1,
        2);

    assertThat(events).singleElement();
    assertThat(events.getFirst().actorHash()).isNotBlank().doesNotContain(actor);
    assertThat(events.getFirst().turnHash()).isNotBlank().doesNotContain(turn);
    assertThat(events.getFirst().toString()).doesNotContain(actor, turn, "Bearer", "Authorization");

    var failing =
        new ControlPlaneAudit(
            Clock.systemUTC(),
            event -> {
              throw new IllegalStateException("audit-secret");
            });
    assertThatCode(
            () -> failing.record("AUTHENTICATE", "ACCEPTED", null, "request-2", actor, null, 0, 0))
        .doesNotThrowAnyException();
  }
}
