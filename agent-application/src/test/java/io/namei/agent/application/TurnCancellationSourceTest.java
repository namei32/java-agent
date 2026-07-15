package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.channel.TurnCancellationCode;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TurnCancellationSourceTest {
  @Test
  void recordsTheFirstReasonAndInvokesEachActiveCallbackOnce() {
    var source = new TurnCancellationSource();
    var callbacks = new AtomicInteger();
    source.token().onCancellation(callbacks::incrementAndGet);

    assertThat(source.cancel(TurnCancellationCode.CHANNEL_DISCONNECTED)).isTrue();
    assertThat(source.cancel(TurnCancellationCode.BACKPRESSURE_EXCEEDED)).isFalse();
    assertThat(source.cancel()).isFalse();

    assertThat(source.token().isCancellationRequested()).isTrue();
    assertThat(source.token().reason()).isEqualTo(TurnCancellationCode.CHANNEL_DISCONNECTED);
    assertThat(callbacks).hasValue(1);
  }

  @Test
  void invokesLateRegistrationImmediatelyAndHonorsClosedRegistration() {
    var source = new TurnCancellationSource();
    var closedCallback = new AtomicInteger();
    var activeCallback = new AtomicInteger();
    var registration = source.token().onCancellation(closedCallback::incrementAndGet);
    registration.close();

    source.cancel(TurnCancellationCode.SHUTDOWN);
    source.token().onCancellation(activeCallback::incrementAndGet);

    assertThat(closedCallback).hasValue(0);
    assertThat(activeCallback).hasValue(1);
    assertThat(source.token().reason()).isEqualTo(TurnCancellationCode.SHUTDOWN);
  }

  @Test
  void neverCancelledTokenHasStableDefaultReason() {
    assertThat(TurnCancellation.none().isCancellationRequested()).isFalse();
    assertThat(TurnCancellation.none().reason()).isEqualTo(TurnCancellationCode.REQUESTED);
  }
}
