package io.namei.agent.bootstrap.telegram;

import java.time.Duration;
import java.util.Objects;

public final class TelegramDeliveryPolicy {
  private final TelegramBotApi api;
  private final ChannelSleeper sleeper;
  private final Duration maxRetryAfter;

  public TelegramDeliveryPolicy(
      TelegramBotApi api, ChannelSleeper sleeper, Duration maxRetryAfter) {
    this.api = Objects.requireNonNull(api, "api");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    if (maxRetryAfter == null
        || maxRetryAfter.isZero()
        || maxRetryAfter.isNegative()
        || maxRetryAfter.compareTo(TelegramProperties.MAX_RETRY_AFTER) > 0) {
      throw new IllegalArgumentException("Telegram maxRetryAfter 超出允许范围");
    }
    this.maxRetryAfter = maxRetryAfter;
  }

  public void send(long chatId, String text) {
    try {
      api.sendMessage(chatId, text);
      return;
    } catch (TelegramApiException firstFailure) {
      Duration retryAfter = retryAfter(firstFailure);
      if (retryAfter == null || retryAfter.compareTo(maxRetryAfter) > 0) {
        throw firstFailure;
      }
      try {
        sleeper.sleep(retryAfter);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
      }
    }
    api.sendMessage(chatId, text);
  }

  private static Duration retryAfter(TelegramApiException failure) {
    if (failure.reason() != TelegramApiException.Reason.RATE_LIMITED) {
      return null;
    }
    return failure.retryAfter().orElse(null);
  }
}
