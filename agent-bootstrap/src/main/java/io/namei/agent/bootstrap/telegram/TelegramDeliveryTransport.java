package io.namei.agent.bootstrap.telegram;

import io.namei.agent.application.ChannelDeliveryRequest;
import io.namei.agent.application.ChannelDeliveryResult;
import io.namei.agent.application.ChannelDeliveryTransport;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TelegramDeliveryTransport implements ChannelDeliveryTransport {
  private static final Pattern POSITIVE_CHAT_ID = Pattern.compile("[1-9][0-9]*");

  private final TelegramBotApi api;

  public TelegramDeliveryTransport(TelegramBotApi api) {
    this.api = Objects.requireNonNull(api, "api");
  }

  @Override
  public ChannelDeliveryResult send(ChannelDeliveryRequest request) {
    Objects.requireNonNull(request, "request");
    if (!POSITIVE_CHAT_ID.matcher(request.targetId()).matches()) {
      return new ChannelDeliveryResult.Permanent("TELEGRAM_TARGET_INVALID");
    }
    long chatId;
    try {
      chatId = Long.parseLong(request.targetId());
    } catch (NumberFormatException invalid) {
      return new ChannelDeliveryResult.Permanent("TELEGRAM_TARGET_INVALID");
    }
    if (chatId <= 0) {
      return new ChannelDeliveryResult.Permanent("TELEGRAM_TARGET_INVALID");
    }
    try {
      TelegramSendReceipt receipt = api.sendMessage(chatId, request.payload());
      if (receipt == null) {
        return new ChannelDeliveryResult.Unknown("TELEGRAM_INVALID_RESPONSE");
      }
      return new ChannelDeliveryResult.Confirmed(Long.toString(receipt.messageId()));
    } catch (TelegramApiException failure) {
      return map(failure);
    }
  }

  private static ChannelDeliveryResult map(TelegramApiException failure) {
    return switch (failure.reason()) {
      case RATE_LIMITED ->
          new ChannelDeliveryResult.Retryable(
              failure.retryAfter().orElse(Duration.ZERO), "RATE_LIMITED");
      case UNAUTHORIZED -> new ChannelDeliveryResult.Permanent("TELEGRAM_UNAUTHORIZED");
      case PERMANENT_REJECTION -> new ChannelDeliveryResult.Permanent("TELEGRAM_REQUEST_REJECTED");
      case TIMEOUT, UNAVAILABLE, INVALID_RESPONSE, INTERRUPTED ->
          new ChannelDeliveryResult.Unknown("TELEGRAM_" + failure.reason().name());
    };
  }
}
