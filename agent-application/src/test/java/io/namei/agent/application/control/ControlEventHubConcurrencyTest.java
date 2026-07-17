package io.namei.agent.application.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.kernel.channel.OutboundMessageType;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("failure")
class ControlEventHubConcurrencyTest {
  private static final Instant START = Instant.parse("2026-07-17T00:00:00Z");

  @Test
  void snapshotSubscribeRaceCannotLoseTheStartedBoundary() throws Exception {
    ActiveTurnRegistryTest.MutableClock clock = new ActiveTurnRegistryTest.MutableClock(START);
    ControlTurnRef reference = ActiveTurnRegistryTest.ref(1);
    ActiveTurnRegistry registry =
        new ActiveTurnRegistry(clock, () -> reference, 1, Duration.ofMinutes(5), 1);
    ControlEventHub hub = new ControlEventHub(registry, clock, 1, 2, Duration.ofMinutes(5));
    ActiveTurnRegistration registration =
        registry.register(
            "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), START);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<ControlSubscription> subscription = new AtomicReference<>();

    Thread subscribe =
        Thread.ofVirtual()
            .start(
                () -> {
                  ready.countDown();
                  await(release);
                  subscription.set(hub.subscribe(reference, "actor-a"));
                });
    Thread publish =
        Thread.ofVirtual()
            .start(
                () -> {
                  ready.countDown();
                  await(release);
                  registration.observe(ActiveTurnRegistryTest.started());
                });
    ready.await();
    release.countDown();
    subscribe.join();
    publish.join();

    ControlSubscription actual = subscription.get();
    Optional<ControlSequencedEvent> queued = actual.poll(Duration.ZERO);
    if (actual.opening().lastSequence() == null) {
      assertThat(queued).isPresent();
      assertThat(queued.orElseThrow().projection().type())
          .isEqualTo(OutboundMessageType.TURN_STARTED);
    } else {
      assertThat(actual.opening().lastSequence()).isZero();
      assertThat(queued).isEmpty();
    }
  }

  @Test
  void sessionRevokeWakesBlockedPollAndReleasesCountExactlyOnce() throws Exception {
    ActiveTurnRegistryTest.MutableClock clock = new ActiveTurnRegistryTest.MutableClock(START);
    ControlTurnRef reference = ActiveTurnRegistryTest.ref(1);
    ActiveTurnRegistry registry =
        new ActiveTurnRegistry(clock, () -> reference, 1, Duration.ofMinutes(5), 1);
    ControlEventHub hub = new ControlEventHub(registry, clock, 1, 2, Duration.ofMinutes(5));
    registry.register(
        "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), START);
    ControlSubscription subscription = hub.subscribe(reference, "actor-a");
    CountDownLatch entered = new CountDownLatch(1);
    AtomicReference<Optional<ControlSequencedEvent>> result = new AtomicReference<>();

    Thread poller =
        Thread.ofVirtual()
            .start(
                () -> {
                  entered.countDown();
                  result.set(subscription.poll(Duration.ofSeconds(30)));
                });
    entered.await();
    hub.closeActor("actor-a");
    hub.closeActor("actor-a");
    poller.join();

    assertThat(result.get()).isEmpty();
    assertThat(subscription.closeReason()).contains(ControlSubscriptionCloseReason.SESSION_REVOKED);
    assertThat(hub.subscriberCount()).isZero();
    assertThat(registry.snapshot().activeTurns())
        .extracting(ActiveTurnSnapshot::subscriberCount)
        .containsExactly(0);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
