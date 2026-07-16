package io.namei.agent.bootstrap.telegram;

public record TelegramUpdate(long updateId, TelegramMessage message) {
  @Override
  public String toString() {
    return "TelegramUpdate[sensitiveFields=<redacted>]";
  }
}
