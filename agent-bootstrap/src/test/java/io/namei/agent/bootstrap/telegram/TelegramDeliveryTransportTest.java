package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.application.ChannelDeliveryRequest;
import io.namei.agent.application.ChannelDeliveryResult;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TelegramDeliveryTransportTest {
  @Test
  void confirmsOnlyTheSafeReceiptAndUsesTheRequestTargetAndPayload() {
    var api = new ScriptedApi();
    api.results.add(new TelegramSendReceipt(501));
    var transport = new TelegramDeliveryTransport(api);

    ChannelDeliveryResult result =
        transport.send(new ChannelDeliveryRequest("10001", "纯文本 <b>不解析</b>"));

    assertThat(result).isEqualTo(new ChannelDeliveryResult.Confirmed("501"));
    assertThat(api.sends).containsExactly(new Send(10001, "纯文本 <b>不解析</b>"));
  }

  @Test
  void mapsExplicitRateLimitIncludingMissingDelayWithoutRetrying() {
    var api = new ScriptedApi();
    api.failures.add(rateLimited(Duration.ofSeconds(3)));
    api.failures.add(rateLimited(null));
    var transport = new TelegramDeliveryTransport(api);

    ChannelDeliveryResult bounded = transport.send(new ChannelDeliveryRequest("10001", "first"));
    ChannelDeliveryResult missing = transport.send(new ChannelDeliveryRequest("10001", "second"));

    assertThat(bounded)
        .isEqualTo(new ChannelDeliveryResult.Retryable(Duration.ofSeconds(3), "RATE_LIMITED"));
    assertThat(missing)
        .isEqualTo(new ChannelDeliveryResult.Retryable(Duration.ZERO, "RATE_LIMITED"));
    assertThat(api.calls).hasValue(2);
  }

  @Test
  void mapsProvableRejectionsToPermanentResults() {
    var api = new ScriptedApi();
    api.failures.add(new TelegramApiException(TelegramApiException.Reason.UNAUTHORIZED));
    api.failures.add(new TelegramApiException(TelegramApiException.Reason.PERMANENT_REJECTION));
    var transport = new TelegramDeliveryTransport(api);

    assertThat(transport.send(new ChannelDeliveryRequest("10001", "first")))
        .isEqualTo(new ChannelDeliveryResult.Permanent("TELEGRAM_UNAUTHORIZED"));
    assertThat(transport.send(new ChannelDeliveryRequest("10001", "second")))
        .isEqualTo(new ChannelDeliveryResult.Permanent("TELEGRAM_REQUEST_REJECTED"));
  }

  @Test
  void mapsEveryUncertainFailureToUnknownAndPreservesInterrupt() {
    for (TelegramApiException.Reason reason :
        List.of(
            TelegramApiException.Reason.TIMEOUT,
            TelegramApiException.Reason.UNAVAILABLE,
            TelegramApiException.Reason.INVALID_RESPONSE,
            TelegramApiException.Reason.INTERRUPTED)) {
      var api = new ScriptedApi();
      api.failures.add(new TelegramApiException(reason));

      ChannelDeliveryResult result =
          new TelegramDeliveryTransport(api).send(new ChannelDeliveryRequest("10001", "payload"));

      assertThat(result).isInstanceOf(ChannelDeliveryResult.Unknown.class);
      assertThat(((ChannelDeliveryResult.Unknown) result).code())
          .isEqualTo("TELEGRAM_" + reason.name());
    }
  }

  @Test
  void invalidTelegramTargetIsPermanentAndNeverCrossesNetworkBoundary() {
    var api = new ScriptedApi();
    var transport = new TelegramDeliveryTransport(api);

    ChannelDeliveryResult result =
        transport.send(new ChannelDeliveryRequest("not-a-chat-id", "payload"));

    assertThat(result).isEqualTo(new ChannelDeliveryResult.Permanent("TELEGRAM_TARGET_INVALID"));
    assertThat(api.calls).hasValue(0);

    assertThat(transport.send(new ChannelDeliveryRequest("+10001", "payload")))
        .isEqualTo(new ChannelDeliveryResult.Permanent("TELEGRAM_TARGET_INVALID"));
    assertThat(api.calls).hasValue(0);
  }

  private static TelegramApiException rateLimited(Duration retryAfter) {
    return new TelegramApiException(TelegramApiException.Reason.RATE_LIMITED, retryAfter);
  }

  private record Send(long chatId, String text) {}

  private static final class ScriptedApi implements TelegramBotApi {
    private final ArrayDeque<TelegramSendReceipt> results = new ArrayDeque<>();
    private final ArrayDeque<TelegramApiException> failures = new ArrayDeque<>();
    private final java.util.ArrayList<Send> sends = new java.util.ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
      throw new UnsupportedOperationException("测试不使用 Poll");
    }

    @Override
    public TelegramSendReceipt sendMessage(long chatId, String text) {
      calls.incrementAndGet();
      sends.add(new Send(chatId, text));
      if (!failures.isEmpty()) {
        throw failures.removeFirst();
      }
      return results.removeFirst();
    }
  }
}
