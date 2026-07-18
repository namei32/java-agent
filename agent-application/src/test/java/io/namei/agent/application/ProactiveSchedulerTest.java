package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.port.ProactiveJobStore;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProactiveSchedulerTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void tickClaimsRunsAndCommitsOutsideStoreTransaction() {
    var store = new ScriptedStore(lease());
    var executed = new AtomicBoolean();
    var scheduler =
        scheduler(
            store,
            (job, cancellation) -> {
              executed.set(true);
              assertThat(cancellation.isCancellationRequested()).isFalse();
              return ProactiveJobState.SUCCEEDED;
            });

    assertThat(scheduler.tick()).isEqualTo(ProactiveSchedulerStep.COMPLETED);
    assertThat(executed).isTrue();
    assertThat(store.completedState).isEqualTo(ProactiveJobState.SUCCEEDED);
    assertThat(store.recovered).isOne();
    scheduler.close();
  }

  @Test
  void closeCancelsAnActiveParentAndLetsItCommitCancelledWithinTheShutdownBound() throws Exception {
    var store = new ScriptedStore(lease());
    var entered = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var scheduler =
        scheduler(
            store,
            (job, cancellation) -> {
              entered.countDown();
              try (var ignored =
                  cancellation.onCancellation(
                      () -> {
                        cancelled.countDown();
                        release.countDown();
                      })) {
                release.await();
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
              }
              return cancellation.isCancellationRequested()
                  ? ProactiveJobState.CANCELLED
                  : ProactiveJobState.SUCCEEDED;
            });
    scheduler.start();
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

    scheduler.close();

    assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(store.completedState).isEqualTo(ProactiveJobState.CANCELLED);
    assertThat(scheduler.isRunning()).isFalse();
  }

  private static ProactiveScheduler scheduler(
      ProactiveJobStore store, ProactiveJobExecutor executor) {
    return new ProactiveScheduler(
        store,
        executor,
        ReliableTurnStarter.virtualThreads(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        new ProactiveSchedulerSettings(
            "proactive-local",
            Duration.ofMinutes(1),
            Duration.ofHours(1),
            8,
            Duration.ofSeconds(2)));
  }

  private static ProactiveJobLease lease() {
    var job =
        new ScheduledJob(
            ProactiveJobRef.parse("daily-summary"),
            new ProactiveSchedule(ProactiveScheduleKind.AT, NOW, null),
            "a".repeat(64),
            "b".repeat(64),
            ProactiveJobState.CLAIMED,
            1,
            3);
    return new ProactiveJobLease(job, "proactive-local", NOW.plusSeconds(30), 1);
  }

  private static final class ScriptedStore implements ProactiveJobStore {
    private final ProactiveJobLease lease;
    private int recovered;
    private ProactiveJobState completedState;
    private boolean claimed;

    private ScriptedStore(ProactiveJobLease lease) {
      this.lease = lease;
    }

    @Override
    public void schedule(ScheduledJob job) {}

    @Override
    public Optional<ProactiveJobLease> claimNext(
        Instant now, String ownerId, Duration leaseDuration) {
      if (claimed) {
        return Optional.empty();
      }
      claimed = true;
      return Optional.of(lease);
    }

    @Override
    public Optional<ProactiveJobLease> markRunning(ProactiveJobLease value, Instant now) {
      return Optional.of(value.running());
    }

    @Override
    public boolean complete(
        ProactiveJobLease value, ProactiveJobState terminalState, Instant completedAt) {
      completedState = terminalState;
      return true;
    }

    @Override
    public int recoverExpired(Instant now, int limit) {
      recovered++;
      return 0;
    }
  }
}
