package io.namei.agent.bootstrap.telegram;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundSequenceValidator;
import java.util.Objects;

public final class TelegramTerminalRenderer {
  private final long chatId;
  private final TelegramTextChunker chunker;
  private final TelegramDeliveryPolicy delivery;
  private final OutboundSequenceValidator validator;

  public TelegramTerminalRenderer(
      InboundMessage inbound,
      long chatId,
      TelegramTextChunker chunker,
      TelegramDeliveryPolicy delivery) {
    Objects.requireNonNull(inbound, "inbound");
    if (chatId <= 0) {
      throw new IllegalArgumentException("Telegram chatId 必须为正数");
    }
    String externalId = Long.toString(chatId);
    if (!inbound.route().channel().equals("telegram")
        || !inbound.route().conversationId().equals(externalId)
        || !inbound.sessionId().equals("telegram:" + externalId)) {
      throw new IllegalArgumentException("Telegram Renderer 身份不一致");
    }
    this.chatId = chatId;
    this.chunker = Objects.requireNonNull(chunker, "chunker");
    this.delivery = Objects.requireNonNull(delivery, "delivery");
    this.validator = new OutboundSequenceValidator(inbound);
  }

  public synchronized void accept(OutboundMessage message) {
    validator.accept(message);
    String terminalText = terminalText(message);
    if (terminalText == null) {
      return;
    }
    for (String chunk : chunker.split(terminalText)) {
      delivery.send(chatId, chunk);
    }
  }

  public boolean isTerminal() {
    return validator.isTerminal();
  }

  private static String terminalText(OutboundMessage message) {
    return switch (message.type()) {
      case TURN_STARTED, CONTENT_DELTA -> null;
      case TURN_COMPLETED -> message.content();
      case TURN_CANCELLED -> "请求已取消（" + message.code() + "）";
      case TURN_FAILED -> "请求失败（" + message.code() + "）" + (message.retryable() ? "，请重新发送。" : "");
    };
  }
}
