package io.namei.agent.bootstrap.telegram;

import java.time.Instant;

public record TelegramMessage(
    long messageId,
    Instant occurredAt,
    long chatId,
    String chatType,
    long senderId,
    boolean senderBot,
    String text) {
  @Override
  public String toString() {
    return "TelegramMessage[sensitiveFields=<redacted>]";
  }
}
