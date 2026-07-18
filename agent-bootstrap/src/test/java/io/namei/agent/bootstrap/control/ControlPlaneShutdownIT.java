package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("failure")
class ControlPlaneShutdownIT {
  @Test
  void oneSharedDeadlineWaitsForAnActiveStreamToFinish() throws Exception {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var tracker = new ControlStreamTracker();
    var sessionsClosed = new CountDownLatch(1);
    var sessions =
        new OperatorSessionStore(
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            actor -> sessionsClosed.countDown());
    sessions.create();
    var auditEvents = new ArrayList<ControlAuditEvent>();
    var coordinator =
        new ControlPlaneShutdownCoordinator(
            runtime,
            sessions,
            tracker,
            ControlPlaneStatusServiceTest.properties(),
            new ControlPlaneAudit(Clock.systemUTC(), auditEvents::add),
            Clock.systemUTC());
    var activeStream = tracker.open().orElseThrow();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var shutdown = executor.submit(coordinator::close);
      assertThat(sessionsClosed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(shutdown.isDone()).isFalse();

      activeStream.close();
      shutdown.get(2, TimeUnit.SECONDS);
    }

    assertThat(runtime.isClosed()).isTrue();
    assertThat(sessions.size()).isZero();
    assertThat(tracker.activeCount()).isZero();
    assertThat(auditEvents)
        .noneSatisfy(event -> assertThat(event.code()).isEqualTo("CONTROL_SHUTDOWN_TIMEOUT"));
  }

  @Test
  void sharedDeadlineTimesOutOnceAndEmitsOnlyTheStableAuditCode() {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var tracker = new ControlStreamTracker();
    var sessions =
        new OperatorSessionStore(
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            actor -> {});
    var auditEvents = new ArrayList<ControlAuditEvent>();
    var coordinator =
        new ControlPlaneShutdownCoordinator(
            runtime,
            sessions,
            tracker,
            properties(Duration.ofMillis(100)),
            new ControlPlaneAudit(Clock.systemUTC(), auditEvents::add),
            Clock.systemUTC());
    var activeStream = tracker.open().orElseThrow();

    coordinator.close();

    assertThat(runtime.isClosed()).isTrue();
    assertThat(tracker.open()).isEmpty();
    assertThat(tracker.activeCount()).isOne();
    assertThat(auditEvents)
        .filteredOn(event -> event.action().equals("CONTROL_SHUTDOWN"))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.result()).isEqualTo("TIMED_OUT");
              assertThat(event.code()).isEqualTo("CONTROL_SHUTDOWN_TIMEOUT");
              assertThat(event.actorHash()).isEmpty();
              assertThat(event.turnHash()).isEmpty();
            });
    activeStream.close();
  }

  @ParameterizedTest
  @MethodSource("orders")
  void bothSpringDestroyOrdersClearSessionsStreamsAndRegistryWithoutCancellingTurn(
      boolean sessionsFirst) {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var source = new TurnCancellationSource();
    var registration =
        runtime.register(
            "telegram",
            ControlCancellationHandle.from(source),
            Instant.parse("2026-07-18T00:00:00Z"));
    var store =
        new OperatorSessionStore(
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            runtime.eventHub()::closeActor);
    OperatorSessionCreated created = store.create();
    String actor = store.authenticate(created.accessToken()).orElseThrow().actorRef();
    runtime.eventHub().subscribe(registration.turnRef().orElseThrow(), actor);

    if (sessionsFirst) {
      store.close();
      runtime.close();
    } else {
      runtime.close();
      store.close();
    }

    assertThat(store.size()).isZero();
    assertThat(runtime.eventHub().subscriberCount()).isZero();
    assertThat(runtime.registry().snapshot().activeTurns()).isEmpty();
    assertThat(source.token().isCancellationRequested()).isFalse();
  }

  private static Stream<Boolean> orders() {
    return Stream.of(true, false);
  }

  private static ControlPlaneProperties properties(Duration shutdownTimeout) {
    return new ControlPlaneProperties(
        "LOOPBACK",
        Duration.ofMinutes(15),
        4,
        128,
        Duration.ofMinutes(5),
        1024,
        8,
        64,
        Duration.ofSeconds(15),
        Duration.ofMinutes(15),
        shutdownTimeout);
  }
}
