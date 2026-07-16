package io.namei.agent.bootstrap.telegram;

import java.util.regex.Pattern;

public final class TelegramBotToken {
  private static final Pattern TOKEN = Pattern.compile("[1-9][0-9]{0,19}:[A-Za-z0-9_-]{20,128}");

  private final String value;

  public TelegramBotToken(String value) {
    if (value == null || !TOKEN.matcher(value).matches()) {
      throw new IllegalArgumentException("Telegram Bot Token 格式无效");
    }
    this.value = value;
  }

  String value() {
    return value;
  }

  @Override
  public String toString() {
    return "TelegramBotToken[value=<redacted>]";
  }
}
