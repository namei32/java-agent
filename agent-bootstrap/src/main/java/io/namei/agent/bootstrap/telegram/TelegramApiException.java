package io.namei.agent.bootstrap.telegram;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class TelegramApiException extends RuntimeException {
  public enum Reason {
    UNAUTHORIZED,
    RATE_LIMITED,
    TIMEOUT,
    UNAVAILABLE,
    INVALID_RESPONSE,
    INTERRUPTED
  }

  private final Reason reason;
  private final Duration retryAfter;

  TelegramApiException(Reason reason) {
    this(reason, null);
  }

  TelegramApiException(Reason reason, Duration retryAfter) {
    super(message(Objects.requireNonNull(reason, "reason")));
    if (retryAfter != null && (retryAfter.isZero() || retryAfter.isNegative())) {
      throw new IllegalArgumentException("retryAfter 必须为正数");
    }
    this.reason = reason;
    this.retryAfter = retryAfter;
  }

  public Reason reason() {
    return reason;
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }

  private static String message(Reason reason) {
    return switch (reason) {
      case UNAUTHORIZED -> "Telegram API 认证失败";
      case RATE_LIMITED -> "Telegram API 请求被限流";
      case TIMEOUT -> "Telegram API 请求超时";
      case UNAVAILABLE -> "Telegram API 暂时不可用";
      case INVALID_RESPONSE -> "Telegram API 响应无效";
      case INTERRUPTED -> "Telegram API 请求被中断";
    };
  }
}
