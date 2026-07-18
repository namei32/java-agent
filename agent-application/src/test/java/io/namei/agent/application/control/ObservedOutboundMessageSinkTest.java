package io.namei.agent.application.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.OutboundMessageSink;
import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.kernel.channel.OutboundMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservedOutboundMessageSinkTest {
  private static final Instant START = Instant.parse("2026-07-17T00:00:00Z");

  @Test
  void observesOnlyAfterPrimaryAcceptsTheMessage() {
    ActiveTurnRegistry registry = registry();
    ActiveTurnRegistration registration = registration(registry);
    List<OutboundMessage> accepted = new ArrayList<>();
    ObservedOutboundMessageSink sink = new ObservedOutboundMessageSink(accepted::add, registration);

    sink.publish(ActiveTurnRegistryTest.started());

    assertThat(accepted).containsExactly(ActiveTurnRegistryTest.started());
    assertThat(registry.snapshot().activeTurns())
        .extracting(ActiveTurnSnapshot::lastSequence)
        .containsExactly(0L);
  }

  @Test
  void primaryRejectionProducesNoControlObservation() {
    ActiveTurnRegistry registry = registry();
    ActiveTurnRegistration registration = registration(registry);
    OutboundMessageSink primary =
        message -> {
          throw new PrimaryRejectedException();
        };
    ObservedOutboundMessageSink sink = new ObservedOutboundMessageSink(primary, registration);

    assertThatThrownBy(() -> sink.publish(ActiveTurnRegistryTest.started()))
        .isInstanceOf(PrimaryRejectedException.class);
    assertThat(registry.snapshot().activeTurns())
        .extracting(ActiveTurnSnapshot::lastSequence)
        .containsExactly((Long) null);
  }

  @Test
  void observerRuntimeFailureCannotChangePrimarySuccess() {
    ActiveTurnRegistry registry = registry();
    ActiveTurnRegistration registration = registration(registry);
    List<OutboundMessage> accepted = new ArrayList<>();
    ObservedOutboundMessageSink sink = new ObservedOutboundMessageSink(accepted::add, registration);

    sink.publish(ActiveTurnRegistryTest.delta(1, "缺少 Started"));

    assertThat(accepted).containsExactly(ActiveTurnRegistryTest.delta(1, "缺少 Started"));
    assertThat(registry.snapshot().activeTurns()).isEmpty();
    assertThat(registry.snapshot().terminalTombstones()).isEqualTo(1);
  }

  private static ActiveTurnRegistry registry() {
    return new ActiveTurnRegistry(
        new ActiveTurnRegistryTest.MutableClock(START),
        () -> ActiveTurnRegistryTest.ref(1),
        1,
        Duration.ofMinutes(5),
        1);
  }

  private static ActiveTurnRegistration registration(ActiveTurnRegistry registry) {
    return registry.register(
        "telegram", ControlCancellationHandle.from(new TurnCancellationSource()), START);
  }

  private static final class PrimaryRejectedException extends RuntimeException {}
}
