package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageSequence;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedOutboundBufferTest {
  @Test
  void publishesAndConsumesOneStrictlyOrderedTurn() {
    var inbound = inbound();
    var messages = new OutboundMessageSequence(inbound);
    var buffer = new BoundedOutboundBuffer(inbound, 3, Duration.ofSeconds(1));

    buffer.publish(messages.started());
    buffer.publish(messages.delta("片段"));
    buffer.publish(messages.completed("完整回答"));

    assertThat(buffer.size()).isEqualTo(3);
    assertThat(buffer.poll(Duration.ZERO)).containsInstanceOf(OutboundMessage.class);
    assertThat(buffer.poll(Duration.ZERO).orElseThrow().content()).isEqualTo("片段");
    assertThat(buffer.poll(Duration.ZERO).orElseThrow().content()).isEqualTo("完整回答");
    assertThat(buffer.poll(Duration.ZERO)).isEmpty();
    assertThat(buffer.isTerminal()).isTrue();
  }

  @Test
  void fullBufferTimesOutWithoutDroppingAndCancelsTheTurn() {
    var inbound = inbound();
    var messages = new OutboundMessageSequence(inbound);
    var buffer = new BoundedOutboundBuffer(inbound, 1, Duration.ofNanos(1));
    var started = messages.started();
    buffer.publish(started);

    assertThatThrownBy(() -> buffer.publish(messages.delta("不能静默丢弃")))
        .isInstanceOf(OutboundDeliveryException.class)
        .extracting(exception -> ((OutboundDeliveryException) exception).reason())
        .isEqualTo(OutboundDeliveryException.Reason.BACKPRESSURE_EXCEEDED);

    assertThat(buffer.size()).isEqualTo(1);
    assertThat(buffer.poll(Duration.ZERO)).contains(started);
    assertThat(buffer.cancellation().reason())
        .isEqualTo(TurnCancellationCode.BACKPRESSURE_EXCEEDED);
    assertThat(buffer.cancellation().isCancellationRequested()).isTrue();
  }

  @Test
  void disconnectClearsPreviewRejectsNewMessagesAndCancelsWithOneReason() {
    var inbound = inbound();
    var messages = new OutboundMessageSequence(inbound);
    var buffer = new BoundedOutboundBuffer(inbound, 2, Duration.ofSeconds(1));
    buffer.publish(messages.started());

    assertThat(buffer.disconnect()).isTrue();
    assertThat(buffer.disconnect()).isFalse();

    assertThat(buffer.poll(Duration.ZERO)).isEmpty();
    assertThat(buffer.cancellation().reason())
        .isEqualTo(TurnCancellationCode.CHANNEL_DISCONNECTED);
    assertThatThrownBy(() -> buffer.publish(messages.delta("迟到")))
        .isInstanceOf(OutboundDeliveryException.class)
        .extracting(exception -> ((OutboundDeliveryException) exception).reason())
        .isEqualTo(OutboundDeliveryException.Reason.CHANNEL_DISCONNECTED);
  }

  @Test
  void disconnectWakesAPublisherWaitingForCapacity() throws Exception {
    var inbound = inbound();
    var messages = new OutboundMessageSequence(inbound);
    var buffer = new BoundedOutboundBuffer(inbound, 1, Duration.ofSeconds(10));
    buffer.publish(messages.started());
    var publisherThread = new AtomicReference<Thread>();
    var entered = new CountDownLatch(1);

    try (var executor = Executors.newSingleThreadExecutor()) {
      var blocked =
          executor.submit(
              () -> {
                publisherThread.set(Thread.currentThread());
                entered.countDown();
                buffer.publish(messages.delta("等待容量"));
                return null;
              });
      entered.await();
      awaitWaiting(publisherThread.get());

      buffer.disconnect();

      assertThatThrownBy(blocked::get)
          .hasCauseInstanceOf(OutboundDeliveryException.class)
          .rootCause()
          .extracting(cause -> ((OutboundDeliveryException) cause).reason())
          .isEqualTo(OutboundDeliveryException.Reason.CHANNEL_DISCONNECTED);
    }
  }

  @Test
  void interruptedBackpressureRestoresInterruptAndCancelsSafely() {
    var inbound = inbound();
    var messages = new OutboundMessageSequence(inbound);
    var buffer = new BoundedOutboundBuffer(inbound, 1, Duration.ofSeconds(1));
    buffer.publish(messages.started());

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> buffer.publish(messages.delta("中断")))
          .isInstanceOf(OutboundDeliveryException.class)
          .extracting(exception -> ((OutboundDeliveryException) exception).reason())
          .isEqualTo(OutboundDeliveryException.Reason.INTERRUPTED);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(buffer.cancellation().reason()).isEqualTo(TurnCancellationCode.REQUESTED);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void rejectsWrongIdentityMissingStartAndEventsAfterTerminal() {
    var inbound = inbound();
    var missingStart = new BoundedOutboundBuffer(inbound, 2, Duration.ofSeconds(1));
    assertInvalid(
        () ->
            missingStart.publish(
                OutboundMessage.delta(
                    inbound.turnId(), inbound.sessionId(), inbound.route(), 1, "缺开始")));

    var wrongTurn = new BoundedOutboundBuffer(inbound, 2, Duration.ofSeconds(1));
    assertInvalid(
        () ->
            wrongTurn.publish(
                OutboundMessage.started("other-turn", inbound.sessionId(), inbound.route())));

    var messages = new OutboundMessageSequence(inbound);
    var terminal = new BoundedOutboundBuffer(inbound, 2, Duration.ofSeconds(1));
    terminal.publish(messages.started());
    terminal.publish(messages.completed("完成"));
    assertInvalid(() -> terminal.publish(OutboundMessage.delta(
        inbound.turnId(), inbound.sessionId(), inbound.route(), 2, "过晚")));
  }

  @Test
  void validatesCapacityAndPublishDeadlineBounds() {
    assertThatThrownBy(() -> new BoundedOutboundBuffer(inbound(), 0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedOutboundBuffer(inbound(), 1025, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedOutboundBuffer(inbound(), 1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedOutboundBuffer(inbound(), 1, Duration.ofSeconds(31)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertInvalid(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(OutboundDeliveryException.class)
        .extracting(exception -> ((OutboundDeliveryException) exception).reason())
        .isEqualTo(OutboundDeliveryException.Reason.INVALID_MESSAGE);
  }

  private static void awaitWaiting(Thread thread) {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (thread.getState() != Thread.State.WAITING
        && thread.getState() != Thread.State.TIMED_WAITING) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("Publisher 未进入等待状态");
      }
      Thread.onSpinWait();
    }
  }

  private static InboundMessage inbound() {
    return new InboundMessage(
        1,
        "message-1",
        "turn-1",
        "cli:demo",
        new MessageRoute("cli", "demo"),
        "local-user",
        "问题",
        Instant.parse("2026-07-15T00:00:00Z"));
  }
}
