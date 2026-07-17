package io.namei.agent.application.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.kernel.channel.OutboundMessageType;
import io.namei.agent.kernel.control.ControlTurnRef;
import io.namei.agent.kernel.control.ControlTurnState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlEventHubTest {
  private static final Instant START = Instant.parse("2026-07-17T00:00:00Z");

  @Test
  void opensFutureOnlySubscriptionsWithIndependentDeliverySequences() {
    Fixture fixture = fixture(2, 4, Duration.ofMinutes(5), ref(1));
    ActiveTurnRegistration registration = fixture.register(new TurnCancellationSource());
    ControlTurnRef turnRef = registration.turnRef().orElseThrow();
    ControlSubscription first = fixture.hub.subscribe(turnRef, "actor-a");
    ControlSubscription second = fixture.hub.subscribe(turnRef, "actor-b");

    assertThat(first.opening())
        .isEqualTo(new ControlStreamOpening(turnRef, ControlTurnState.ACTIVE, null, START, false));
    assertThat(fixture.registry.snapshot().activeTurns())
        .extracting(ActiveTurnSnapshot::subscriberCount)
        .containsExactly(2);

    registration.observe(ActiveTurnRegistryTest.started());
    registration.observe(ActiveTurnRegistryTest.delta(1, "片段"));

    assertEvent(first, 1, 0, OutboundMessageType.TURN_STARTED);
    assertEvent(first, 2, 1, OutboundMessageType.CONTENT_DELTA);
    assertEvent(second, 1, 0, OutboundMessageType.TURN_STARTED);
    assertEvent(second, 2, 1, OutboundMessageType.CONTENT_DELTA);
  }

  @Test
  void terminalEventDrainsThenClosesAndRemovesTheActiveTurn() {
    Fixture fixture = fixture(1, 4, Duration.ofMinutes(5), ref(1));
    ActiveTurnRegistration registration = fixture.register(new TurnCancellationSource());
    ControlSubscription subscription =
        fixture.hub.subscribe(registration.turnRef().orElseThrow(), "actor-a");

    registration.observe(ActiveTurnRegistryTest.started());
    registration.observe(ActiveTurnRegistryTest.completed(1, "完成"));

    assertEvent(subscription, 1, 0, OutboundMessageType.TURN_STARTED);
    assertEvent(subscription, 2, 1, OutboundMessageType.TURN_COMPLETED);
    assertThat(subscription.poll(Duration.ZERO)).isEmpty();
    assertThat(subscription.closeReason()).contains(ControlSubscriptionCloseReason.TERMINAL);
    assertThat(fixture.registry.snapshot().activeTurns()).isEmpty();
  }

  @Test
  void slowConsumerIsRemovedWithoutCancellingTurnOrOtherSubscriber() {
    Fixture fixture = fixture(2, 1, Duration.ofMinutes(5), ref(1));
    TurnCancellationSource cancellation = new TurnCancellationSource();
    ActiveTurnRegistration registration = fixture.register(cancellation);
    ControlTurnRef turnRef = registration.turnRef().orElseThrow();
    ControlSubscription slow = fixture.hub.subscribe(turnRef, "actor-slow");
    ControlSubscription healthy = fixture.hub.subscribe(turnRef, "actor-healthy");

    registration.observe(ActiveTurnRegistryTest.started());
    assertEvent(healthy, 1, 0, OutboundMessageType.TURN_STARTED);
    registration.observe(ActiveTurnRegistryTest.delta(1, "片段"));

    assertThat(slow.poll(Duration.ZERO)).isEmpty();
    assertThat(slow.closeReason()).contains(ControlSubscriptionCloseReason.SLOW_CONSUMER);
    assertEvent(healthy, 2, 1, OutboundMessageType.CONTENT_DELTA);
    assertThat(fixture.registry.snapshot().activeTurns())
        .extracting(ActiveTurnSnapshot::subscriberCount)
        .containsExactly(1);
    assertThat(cancellation.token().isCancellationRequested()).isFalse();
  }

  @Test
  void enforcesGlobalCapacityAndDistinguishesUnknownFromTerminal() {
    Fixture fixture = fixture(1, 2, Duration.ofMinutes(5), ref(1), ref(2));
    ActiveTurnRegistration first = fixture.register(new TurnCancellationSource());
    ActiveTurnRegistration second = fixture.register(new TurnCancellationSource());
    ControlTurnRef firstRef = first.turnRef().orElseThrow();
    ControlTurnRef secondRef = second.turnRef().orElseThrow();
    fixture.hub.subscribe(firstRef, "actor-a");

    assertThatThrownBy(() -> fixture.hub.subscribe(secondRef, "actor-b"))
        .isInstanceOfSatisfying(
            ControlSubscriptionException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(ControlSubscriptionException.Reason.CAPACITY_EXCEEDED));

    first.observe(ActiveTurnRegistryTest.started());
    first.observe(ActiveTurnRegistryTest.completed(1, "完成"));
    assertThatThrownBy(() -> fixture.hub.subscribe(firstRef, "actor-c"))
        .isInstanceOfSatisfying(
            ControlSubscriptionException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(ControlSubscriptionException.Reason.ALREADY_TERMINAL));
    assertThatThrownBy(() -> fixture.hub.subscribe(ref(9), "actor-c"))
        .isInstanceOfSatisfying(
            ControlSubscriptionException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(ControlSubscriptionException.Reason.TURN_NOT_FOUND));
  }

  @Test
  void actorRevocationLifetimeAndShutdownReleaseWithoutTurnCancellation() {
    Fixture fixture = fixture(3, 2, Duration.ofMinutes(5), ref(1));
    TurnCancellationSource cancellation = new TurnCancellationSource();
    ActiveTurnRegistration registration = fixture.register(cancellation);
    ControlTurnRef turnRef = registration.turnRef().orElseThrow();
    ControlSubscription revoked = fixture.hub.subscribe(turnRef, "actor-a");
    ControlSubscription expired = fixture.hub.subscribe(turnRef, "actor-b");
    ControlSubscription shutdown = fixture.hub.subscribe(turnRef, "actor-c");

    fixture.hub.closeActor("actor-a");
    fixture.hub.closeActor("actor-a");
    fixture.clock.advance(Duration.ofMinutes(5).plusNanos(1));
    assertThat(expired.poll(Duration.ZERO)).isEmpty();
    fixture.hub.close();
    fixture.hub.close();

    assertThat(revoked.closeReason()).contains(ControlSubscriptionCloseReason.SESSION_REVOKED);
    assertThat(expired.closeReason()).contains(ControlSubscriptionCloseReason.LIFETIME_EXCEEDED);
    assertThat(shutdown.closeReason()).contains(ControlSubscriptionCloseReason.SHUTDOWN);
    assertThat(cancellation.token().isCancellationRequested()).isFalse();
    assertThat(fixture.hub.subscriberCount()).isZero();
  }

  @Test
  void controlledWriterBoundaryAndClientDisconnectReleaseExactlyOnce() {
    Fixture fixture = fixture(1, 2, Duration.ofMinutes(5), ref(1));
    TurnCancellationSource cancellation = new TurnCancellationSource();
    ActiveTurnRegistration registration = fixture.register(cancellation);
    ControlSubscription subscription =
        fixture.hub.subscribe(registration.turnRef().orElseThrow(), "actor-a");
    List<ControlSequencedEvent> written = new ArrayList<>();
    registration.observe(ActiveTurnRegistryTest.started());

    assertThat(subscription.writeNext(written::add, Duration.ZERO)).isTrue();
    subscription.close();
    subscription.close();

    assertThat(written).hasSize(1);
    assertThat(written.getFirst().projection().type()).isEqualTo(OutboundMessageType.TURN_STARTED);
    assertThat(subscription.closeReason())
        .contains(ControlSubscriptionCloseReason.CLIENT_DISCONNECTED);
    assertThat(fixture.hub.subscriberCount()).isZero();
    assertThat(cancellation.token().isCancellationRequested()).isFalse();
  }

  private static void assertEvent(
      ControlSubscription subscription,
      long deliverySequence,
      long messageSequence,
      OutboundMessageType type) {
    ControlSequencedEvent event = subscription.poll(Duration.ZERO).orElseThrow();
    assertThat(event.deliverySequence()).isEqualTo(deliverySequence);
    assertThat(event.projection().turnRef()).isEqualTo(subscription.opening().turnRef());
    assertThat(event.projection().sequence()).isEqualTo(messageSequence);
    assertThat(event.projection().type()).isEqualTo(type);
  }

  private static Fixture fixture(
      int maxSubscribers,
      int bufferCapacity,
      Duration lifetime,
      ControlTurnRef first,
      ControlTurnRef... rest) {
    ActiveTurnRegistryTest.MutableClock clock = new ActiveTurnRegistryTest.MutableClock(START);
    ArrayDeque<ControlTurnRef> references = new ArrayDeque<>();
    references.add(first);
    references.addAll(List.of(rest));
    ActiveTurnRegistry registry =
        new ActiveTurnRegistry(
            clock, references::removeFirst, references.size(), Duration.ofMinutes(5), 8);
    return new Fixture(
        clock,
        registry,
        new ControlEventHub(registry, clock, maxSubscribers, bufferCapacity, lifetime));
  }

  private static ControlTurnRef ref(int lastByte) {
    return ActiveTurnRegistryTest.ref(lastByte);
  }

  private record Fixture(
      ActiveTurnRegistryTest.MutableClock clock, ActiveTurnRegistry registry, ControlEventHub hub) {
    private ActiveTurnRegistration register(TurnCancellationSource source) {
      return registry.register("telegram", ControlCancellationHandle.from(source), clock.instant());
    }
  }
}
