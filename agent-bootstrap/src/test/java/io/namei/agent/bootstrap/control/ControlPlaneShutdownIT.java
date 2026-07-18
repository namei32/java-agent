package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("failure")
class ControlPlaneShutdownIT {
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
}
