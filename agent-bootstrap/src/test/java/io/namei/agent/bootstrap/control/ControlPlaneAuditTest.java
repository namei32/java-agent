package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.namei.agent.kernel.control.ControlStableCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ControlPlaneAuditTest {
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
