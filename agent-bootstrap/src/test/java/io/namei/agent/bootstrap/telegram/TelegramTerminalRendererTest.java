package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.ChannelTerminalProjector;
import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageSequence;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.channel.TurnFailureCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramTerminalRendererTest {
  @Test
  void projectsOnlyTheAuthoritativeCompletionWithoutNetworkOrRetryPolicy() {
    ChannelTerminalProjector renderer = renderer();
    var sequence = new OutboundMessageSequence(inbound());
    sequence.started();
    sequence.delta("preview-secret");
    sequence.delta("{\"tool_arguments\":\"must-not-send\"}");

    List<String> parts = renderer.project(sequence.completed("权威最终回答"));

    assertThat(parts).containsExactly("权威最终回答");
    assertThat(String.join("", parts)).doesNotContain("preview-secret", "tool_arguments");
  }

  @Test
  void projectsCancellationAndFailureUsingOnlyStableContractCodes() {
    var cancelled = new OutboundMessageSequence(inbound());
    cancelled.started();
    assertThat(renderer().project(cancelled.cancelled(TurnCancellationCode.REQUESTED)))
        .containsExactly("请求已取消（REQUESTED）");

    var retryable = new OutboundMessageSequence(inbound());
    retryable.started();
    assertThat(renderer().project(retryable.failed(TurnFailureCode.MODEL_TIMEOUT)))
        .containsExactly("请求失败（MODEL_TIMEOUT），请重新发送。");

    var permanent = new OutboundMessageSequence(inbound());
    permanent.started();
    assertThat(renderer().project(permanent.failed(TurnFailureCode.INTERNAL_ERROR)))
        .containsExactly("请求失败（INTERNAL_ERROR）");
  }

  @Test
  void chunksStrictlyInOrderWithoutSplittingEmoji() {
    var sequence = new OutboundMessageSequence(inbound());
    sequence.started();
    String answer = "a".repeat(3999) + "😊" + "b".repeat(4000);

    List<String> parts = renderer().project(sequence.completed(answer));

    assertThat(parts).containsExactly("a".repeat(3999), "😊" + "b".repeat(3998), "bb");
    assertThat(String.join("", parts)).isEqualTo(answer);
  }

  @Test
  void rejectsNonTerminalInputAndReturnsImmutableParts() {
    var sequence = new OutboundMessageSequence(inbound());
    OutboundMessage started = sequence.started();
    OutboundMessage delta = sequence.delta("draft");

    assertThatThrownBy(() -> renderer().project(started))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> renderer().project(delta))
        .isInstanceOf(IllegalArgumentException.class);

    List<String> parts = renderer().project(sequence.completed("answer"));
    assertThatThrownBy(() -> parts.add("changed"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static TelegramTerminalRenderer renderer() {
    return new TelegramTerminalRenderer(new TelegramTextChunker());
  }

  private static InboundMessage inbound() {
    return new InboundMessage(
        1,
        "telegram:10001:42",
        "turn-1",
        "telegram:10001",
        new MessageRoute("telegram", "10001"),
        "10001",
        "问题",
        Instant.parse("2026-07-16T00:00:00Z"));
  }
}
