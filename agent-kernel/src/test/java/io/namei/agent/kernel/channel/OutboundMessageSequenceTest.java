package io.namei.agent.kernel.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class OutboundMessageSequenceTest {
  @Test
  void emitsStrictSequencesAndOneAuthoritativeCompletion() {
    var sequence = new OutboundMessageSequence(inbound());

    var started = sequence.started();
    var first = sequence.delta("你");
    var second = sequence.delta("好");
    var completed = sequence.completed("你好");

    assertThat(
            List.of(started.sequence(), first.sequence(), second.sequence(), completed.sequence()))
        .containsExactly(0L, 1L, 2L, 3L);
    assertThat(completed.type()).isEqualTo(OutboundMessageType.TURN_COMPLETED);
    assertThat(completed.content()).isEqualTo("你好");
    assertThat(sequence.isTerminal()).isTrue();
  }

  @Test
  void permitsCompletionWithoutAnyDelta() {
    var sequence = new OutboundMessageSequence(inbound());

    assertThat(sequence.started().sequence()).isZero();
    assertThat(sequence.completed("完整回答").sequence()).isEqualTo(1);
  }

  @Test
  void rejectsInvalidStateTransitionsWithoutConsumingAValidTerminal() {
    var sequence = new OutboundMessageSequence(inbound());

    assertThatThrownBy(() -> sequence.delta("过早")).isInstanceOf(IllegalStateException.class);
    sequence.started();
    assertThatThrownBy(sequence::started).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> sequence.completed("   "))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(sequence.failed(TurnFailureCode.INTERNAL_ERROR).sequence()).isEqualTo(1);
    assertThatThrownBy(() -> sequence.delta("过晚")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> sequence.cancelled(TurnCancellationCode.REQUESTED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validatorAcceptsOneOrderedTurnAndRejectsEverythingAfterTerminal() {
    var inbound = inbound();
    var generated = new OutboundMessageSequence(inbound);
    var validator = new OutboundSequenceValidator(inbound);

    validator.accept(generated.started());
    validator.accept(generated.delta("片段"));
    validator.accept(generated.cancelled(TurnCancellationCode.REQUESTED));

    assertThat(validator.isTerminal()).isTrue();
    assertThatThrownBy(
            () ->
                validator.accept(
                    OutboundMessage.failed(
                        inbound.turnId(),
                        inbound.sessionId(),
                        inbound.route(),
                        3,
                        TurnFailureCode.INTERNAL_ERROR)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validatorRejectsMissingStartWrongIdentityGapsDuplicatesAndReordering() {
    var inbound = inbound();

    assertThatThrownBy(
            () ->
                new OutboundSequenceValidator(inbound)
                    .accept(
                        OutboundMessage.delta(
                            inbound.turnId(), inbound.sessionId(), inbound.route(), 1, "缺开始")))
        .isInstanceOf(IllegalStateException.class);

    var wrongTurn = new OutboundSequenceValidator(inbound);
    assertThatThrownBy(
            () ->
                wrongTurn.accept(
                    OutboundMessage.started("turn-other", inbound.sessionId(), inbound.route())))
        .isInstanceOf(IllegalArgumentException.class);

    var wrongSession = new OutboundSequenceValidator(inbound);
    assertThatThrownBy(
            () ->
                wrongSession.accept(
                    OutboundMessage.started(inbound.turnId(), "other-session", inbound.route())))
        .isInstanceOf(IllegalArgumentException.class);

    var wrongRoute = new OutboundSequenceValidator(inbound);
    assertThatThrownBy(
            () ->
                wrongRoute.accept(
                    OutboundMessage.started(
                        inbound.turnId(), inbound.sessionId(), new MessageRoute("cli", "other"))))
        .isInstanceOf(IllegalArgumentException.class);

    var gap = new OutboundSequenceValidator(inbound);
    gap.accept(OutboundMessage.started(inbound.turnId(), inbound.sessionId(), inbound.route()));
    assertThatThrownBy(
            () ->
                gap.accept(
                    OutboundMessage.delta(
                        inbound.turnId(), inbound.sessionId(), inbound.route(), 2, "缺口")))
        .isInstanceOf(IllegalArgumentException.class);

    var duplicate = new OutboundSequenceValidator(inbound);
    duplicate.accept(
        OutboundMessage.started(inbound.turnId(), inbound.sessionId(), inbound.route()));
    var first =
        OutboundMessage.delta(inbound.turnId(), inbound.sessionId(), inbound.route(), 1, "一");
    duplicate.accept(first);
    assertThatThrownBy(() -> duplicate.accept(first)).isInstanceOf(IllegalArgumentException.class);

    var reordered = new OutboundSequenceValidator(inbound);
    reordered.accept(
        OutboundMessage.started(inbound.turnId(), inbound.sessionId(), inbound.route()));
    reordered.accept(
        OutboundMessage.delta(inbound.turnId(), inbound.sessionId(), inbound.route(), 1, "一"));
    assertThatThrownBy(
            () ->
                reordered.accept(
                    OutboundMessage.delta(
                        inbound.turnId(), inbound.sessionId(), inbound.route(), 1, "旧序号")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentTerminalRaceHasExactlyOneWinner() throws Exception {
    var sequence = new OutboundMessageSequence(inbound());
    sequence.started();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var complete =
          executor.submit(() -> attemptTerminal(ready, start, () -> sequence.completed("完成")));
      var cancel =
          executor.submit(
              () ->
                  attemptTerminal(
                      ready, start, () -> sequence.cancelled(TurnCancellationCode.REQUESTED)));

      ready.await();
      start.countDown();

      assertThat(List.of(complete.get(), cancel.get())).containsExactlyInAnyOrder(true, false);
      assertThat(sequence.isTerminal()).isTrue();
    }
  }

  private static boolean attemptTerminal(
      CountDownLatch ready, CountDownLatch start, TerminalAction action)
      throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      action.run();
      return true;
    } catch (IllegalStateException expected) {
      return false;
    }
  }

  private static InboundMessage inbound() {
    return new InboundMessage(
        1,
        "msg-1",
        "turn-1",
        "cli:demo",
        new MessageRoute("cli", "demo"),
        "local-user",
        "问题",
        Instant.parse("2026-07-15T00:00:00Z"));
  }

  @FunctionalInterface
  private interface TerminalAction {
    void run();
  }
}
