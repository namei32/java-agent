package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcProactiveJobStoreTest {
  private static final Instant DUE = Instant.parse("2026-07-18T00:00:00Z");
  private static final String TARGET = "a".repeat(64);
  private static final String KEY = "b".repeat(64);

  @TempDir Path tempDir;

  @Test
  void claimsOnceRecoversExpiredLeaseAndRejectsLostOwnerCommit() {
    var schema = schema();
    schema.initialize();
    var store = new JdbcProactiveJobStore(schema);
    store.schedule(atJob("morning-summary", KEY));

    assertThat(store.claimNext(DUE.minusSeconds(1), "worker-a", Duration.ofSeconds(30))).isEmpty();

    var first = store.claimNext(DUE, "worker-a", Duration.ofSeconds(30)).orElseThrow();
    assertThat(first.job().state()).isEqualTo(ProactiveJobState.CLAIMED);
    assertThat(first.job().attempts()).isOne();
    assertThat(store.claimNext(DUE, "worker-b", Duration.ofSeconds(30))).isEmpty();

    assertThat(store.recoverExpired(DUE.plusSeconds(31), 4)).isOne();
    var second =
        store.claimNext(DUE.plusSeconds(31), "worker-b", Duration.ofSeconds(30)).orElseThrow();
    var running = store.markRunning(second, DUE.plusSeconds(32)).orElseThrow();

    assertThat(store.complete(first, ProactiveJobState.SUCCEEDED, DUE.plusSeconds(32))).isFalse();
    assertThat(store.complete(running, ProactiveJobState.SUCCEEDED, DUE.plusSeconds(33))).isTrue();
    assertThat(store.claimNext(DUE.plusSeconds(34), "worker-c", Duration.ofSeconds(30))).isEmpty();
  }

  @Test
  void periodicCompletionSchedulesTheFirstFutureUtcSlotWithoutReplayingMissedIntervals() {
    var schema = schema();
    schema.initialize();
    var store = new JdbcProactiveJobStore(schema);
    store.schedule(
        new ScheduledJob(
            ProactiveJobRef.parse("periodic-summary"),
            new ProactiveSchedule(ProactiveScheduleKind.EVERY, DUE, Duration.ofMinutes(1)),
            TARGET,
            KEY,
            ProactiveJobState.SCHEDULED,
            0,
            3));

    var claimed = store.claimNext(DUE, "worker-a", Duration.ofMinutes(2)).orElseThrow();
    var running = store.markRunning(claimed, DUE.plusSeconds(1)).orElseThrow();
    assertThat(store.complete(running, ProactiveJobState.SUCCEEDED, DUE.plusSeconds(65))).isTrue();

    assertThat(store.claimNext(DUE.plusSeconds(119), "worker-b", Duration.ofSeconds(30))).isEmpty();
    assertThat(store.claimNext(DUE.plusSeconds(120), "worker-b", Duration.ofSeconds(30)))
        .hasValueSatisfying(
            lease -> {
              assertThat(lease.job().schedule().nextRunAt()).isEqualTo(DUE.plusSeconds(120));
              assertThat(lease.job().attempts()).isOne();
            });
  }

  @Test
  void idempotencyKeyCannotScheduleTwoJobs() {
    var schema = schema();
    schema.initialize();
    var store = new JdbcProactiveJobStore(schema);
    store.schedule(atJob("first-summary", KEY));

    assertThatThrownBy(() -> store.schedule(atJob("second-summary", KEY)))
        .isInstanceOf(ProactiveRepositoryException.class)
        .hasMessageContaining("重复");
  }

  @Test
  void listsOnlyActiveJobsWithStableOrderAndBoundedLimit() {
    var schema = schema();
    schema.initialize();
    var store = new JdbcProactiveJobStore(schema);
    store.schedule(atJob("finished-summary", KEY));
    var claimed = store.claimNext(DUE, "worker-a", Duration.ofSeconds(30)).orElseThrow();
    var running = store.markRunning(claimed, DUE.plusSeconds(1)).orElseThrow();
    assertThat(store.complete(running, ProactiveJobState.SUCCEEDED, DUE.plusSeconds(2))).isTrue();

    Instant next = DUE.plus(Duration.ofHours(1));
    store.schedule(atJobAt("zeta-summary", next, "c".repeat(64)));
    store.schedule(atJobAt("alpha-summary", next, "d".repeat(64)));
    store.schedule(atJobAt("later-summary", next.plusSeconds(1), "e".repeat(64)));

    assertThat(store.listActive(2))
        .extracting(snapshot -> snapshot.jobRef().value())
        .containsExactly("alpha-summary", "zeta-summary");
    assertThatThrownBy(() -> store.listActive(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private ProactiveSchemaInitializer schema() {
    return new ProactiveSchemaInitializer(tempDir.resolve("proactive/proactive-runtime.db"), 5_000);
  }

  private static ScheduledJob atJob(String reference, String idempotencyKey) {
    return atJobAt(reference, DUE, idempotencyKey);
  }

  private static ScheduledJob atJobAt(String reference, Instant nextRunAt, String idempotencyKey) {
    return new ScheduledJob(
        ProactiveJobRef.parse(reference),
        new ProactiveSchedule(ProactiveScheduleKind.AT, nextRunAt, null),
        TARGET,
        idempotencyKey,
        ProactiveJobState.SCHEDULED,
        0,
        3);
  }
}
