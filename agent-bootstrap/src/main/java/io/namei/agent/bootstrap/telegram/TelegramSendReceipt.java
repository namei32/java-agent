package io.namei.agent.bootstrap.telegram;

public record TelegramSendReceipt(long messageId) {
  public TelegramSendReceipt {
    if (messageId <= 0) {
      throw new IllegalArgumentException("Telegram messageId 必须为正数");
    }
  }

  @Override
  public String toString() {
    return "TelegramSendReceipt[messageId=<redacted>]";
  }
}
