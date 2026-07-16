package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageSequence;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.channel.TurnFailureCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TelegramTerminalRendererTest {
  @Test
  void ignoresStartedAndDeltasAndSendsOnlyTheAuthoritativeCompletion() {
    var api = new ScriptedApi();
    var renderer = renderer(api, duration -> {});
    var sequence = new OutboundMessageSequence(inbound());

    renderer.accept(sequence.started());
    renderer.accept(sequence.delta("preview-secret"));
    renderer.accept(sequence.delta("{\"tool_arguments\":\"must-not-send\"}"));
    assertThat(api.sends).isEmpty();
    renderer.accept(sequence.completed("权威最终回答"));

    assertThat(api.sends).containsExactly(new Send(10001, "权威最终回答"));
    assertThat(renderer.isTerminal()).isTrue();
    assertThat(api.renderedText()).doesNotContain("preview-secret", "tool_arguments");
  }

  @Test
  void projectsCancellationAndFailureUsingOnlyStableContractCodes() {
    var cancelledApi = new ScriptedApi();
    var cancelled = renderer(cancelledApi, duration -> {});
    var cancelledSequence = new OutboundMessageSequence(inbound());
    cancelled.accept(cancelledSequence.started());
    cancelled.accept(cancelledSequence.cancelled(TurnCancellationCode.REQUESTED));
    assertThat(cancelledApi.renderedText()).isEqualTo("请求已取消（REQUESTED）");

    var retryableApi = new ScriptedApi();
    var retryable = renderer(retryableApi, duration -> {});
    var retryableSequence = new OutboundMessageSequence(inbound());
    retryable.accept(retryableSequence.started());
    retryable.accept(retryableSequence.failed(TurnFailureCode.MODEL_TIMEOUT));
    assertThat(retryableApi.renderedText()).isEqualTo("请求失败（MODEL_TIMEOUT），请重新发送。");

    var permanentApi = new ScriptedApi();
    var permanent = renderer(permanentApi, duration -> {});
    var permanentSequence = new OutboundMessageSequence(inbound());
    permanent.accept(permanentSequence.started());
    permanent.accept(permanentSequence.failed(TurnFailureCode.INTERNAL_ERROR));
    assertThat(permanentApi.renderedText()).isEqualTo("请求失败（INTERNAL_ERROR）");
  }

  @Test
  void sendsAllChunksStrictlyInOrderWithoutSplittingEmoji() {
    var api = new ScriptedApi();
    var renderer = renderer(api, duration -> {});
    var sequence = new OutboundMessageSequence(inbound());
    String answer = "a".repeat(3999) + "😊" + "b".repeat(4000);

    renderer.accept(sequence.started());
    renderer.accept(sequence.completed(answer));

    assertThat(api.sends)
        .extracting(Send::text)
        .containsExactly("a".repeat(3999), "😊" + "b".repeat(3998), "bb");
    assertThat(api.renderedText()).isEqualTo(answer);
  }

  @Test
  void retriesExactlyOnceOnlyForAnExplicitBoundedRateLimit() {
    var api = new ScriptedApi();
    api.failNext(rateLimited(3));
    var sleeps = new ArrayList<Duration>();
    var renderer = renderer(api, sleeps::add);
    var sequence = new OutboundMessageSequence(inbound());

    renderer.accept(sequence.started());
    renderer.accept(sequence.completed("回答"));

    assertThat(api.sends).hasSize(2);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
  }

  @Test
  @Tag("failure")
  void doesNotRetryUncertainFailuresOrRateLimitsOutsideTheBudget() {
    for (TelegramApiException.Reason reason :
        List.of(
            TelegramApiException.Reason.TIMEOUT,
            TelegramApiException.Reason.UNAVAILABLE,
            TelegramApiException.Reason.INVALID_RESPONSE)) {
      var api = new ScriptedApi();
      api.failNext(new TelegramApiException(reason));
      var sleeps = new ArrayList<Duration>();
      var renderer = renderer(api, sleeps::add);
      var sequence = new OutboundMessageSequence(inbound());
      renderer.accept(sequence.started());

      assertThatThrownBy(() -> renderer.accept(sequence.completed("回答")))
          .isInstanceOf(TelegramApiException.class)
          .extracting(exception -> ((TelegramApiException) exception).reason())
          .isEqualTo(reason);
      assertThat(api.sends).hasSize(1);
      assertThat(sleeps).isEmpty();
      assertThat(renderer.isTerminal()).isTrue();
    }

    var overBudget = new ScriptedApi();
    overBudget.failNext(rateLimited(6));
    var sleeps = new ArrayList<Duration>();
    var renderer = renderer(overBudget, sleeps::add);
    var sequence = new OutboundMessageSequence(inbound());
    renderer.accept(sequence.started());
    assertThatThrownBy(() -> renderer.accept(sequence.completed("回答")))
        .isInstanceOf(TelegramApiException.class);
    assertThat(overBudget.sends).hasSize(1);
    assertThat(sleeps).isEmpty();
  }

  @Test
  @Tag("failure")
  void aSecondRateLimitIsReturnedWithoutAThirdAttempt() {
    var api = new ScriptedApi();
    api.failNext(rateLimited(1));
    api.failNext(rateLimited(1));
    var sleeps = new ArrayList<Duration>();
    var renderer = renderer(api, sleeps::add);
    var sequence = new OutboundMessageSequence(inbound());
    renderer.accept(sequence.started());

    assertThatThrownBy(() -> renderer.accept(sequence.completed("回答")))
        .isInstanceOf(TelegramApiException.class)
        .extracting(exception -> ((TelegramApiException) exception).reason())
        .isEqualTo(TelegramApiException.Reason.RATE_LIMITED);
    assertThat(api.sends).hasSize(2);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
  }

  @Test
  @Tag("failure")
  void interruptedRateLimitSleepRestoresTheInterruptFlag() {
    var api = new ScriptedApi();
    api.failNext(rateLimited(1));
    var renderer =
        renderer(
            api,
            duration -> {
              throw new InterruptedException("test-interrupt-secret");
            });
    var sequence = new OutboundMessageSequence(inbound());
    renderer.accept(sequence.started());

    try {
      assertThatThrownBy(() -> renderer.accept(sequence.completed("回答")))
          .isInstanceOf(TelegramApiException.class)
          .hasNoCause()
          .hasMessageNotContaining("test-interrupt-secret")
          .extracting(exception -> ((TelegramApiException) exception).reason())
          .isEqualTo(TelegramApiException.Reason.INTERRUPTED);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void usesTheProductionSequenceValidatorAndNeverAcceptsASecondTerminal() {
    var api = new ScriptedApi();
    var renderer = renderer(api, duration -> {});
    assertThatThrownBy(
            () ->
                renderer.accept(
                    OutboundMessage.delta(
                        inbound().turnId(), inbound().sessionId(), inbound().route(), 1, "缺少开始")))
        .isInstanceOf(IllegalStateException.class);

    var valid = new OutboundMessageSequence(inbound());
    renderer.accept(valid.started());
    renderer.accept(valid.completed("完成"));
    assertThatThrownBy(
            () ->
                renderer.accept(
                    OutboundMessage.completed(
                        inbound().turnId(), inbound().sessionId(), inbound().route(), 2, "第二终态")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(api.sends).hasSize(1);
  }

  private static TelegramTerminalRenderer renderer(ScriptedApi api, ChannelSleeper sleeper) {
    return new TelegramTerminalRenderer(
        inbound(),
        10001,
        new TelegramTextChunker(),
        new TelegramDeliveryPolicy(api, sleeper, Duration.ofSeconds(5)));
  }

  private static TelegramApiException rateLimited(long seconds) {
    return new TelegramApiException(
        TelegramApiException.Reason.RATE_LIMITED, Duration.ofSeconds(seconds));
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

  private record Send(long chatId, String text) {}

  private static final class ScriptedApi implements TelegramBotApi {
    private final ArrayDeque<TelegramApiException> failures = new ArrayDeque<>();
    private final List<Send> sends = new ArrayList<>();

    private void failNext(TelegramApiException failure) {
      failures.addLast(failure);
    }

    private String renderedText() {
      return sends.stream().map(Send::text).reduce("", String::concat);
    }

    @Override
    public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
      throw new UnsupportedOperationException("测试不使用 Poll");
    }

    @Override
    public void sendMessage(long chatId, String text) {
      sends.add(new Send(chatId, text));
      if (!failures.isEmpty()) {
        throw failures.removeFirst();
      }
    }
  }
}
