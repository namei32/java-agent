package io.namei.agent.bootstrap.telegram;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.MessageRoute;
import java.util.Objects;
import java.util.Set;

public final class TelegramUpdateMapper {
  private static final String CHANNEL = "telegram";

  private final Set<Long> allowedUserIds;
  private final TelegramIdGenerator ids;

  public TelegramUpdateMapper(Set<Long> allowedUserIds, TelegramIdGenerator ids) {
    if (allowedUserIds == null || allowedUserIds.isEmpty()) {
      throw new IllegalArgumentException("Telegram Allowlist 不能为空");
    }
    if (allowedUserIds.stream().anyMatch(id -> id == null || id <= 0)) {
      throw new IllegalArgumentException("Telegram Allowlist 包含无效 ID");
    }
    this.allowedUserIds = Set.copyOf(allowedUserIds);
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public TelegramInboundDecision map(TelegramUpdate update) {
    Objects.requireNonNull(update, "update");
    TelegramMessage message = update.message();
    if (message == null) {
      return TelegramInboundDecision.ignored(
          TelegramInboundDecision.IgnoreReason.UNSUPPORTED_UPDATE);
    }
    if (!"private".equals(message.chatType())) {
      return TelegramInboundDecision.ignored(TelegramInboundDecision.IgnoreReason.NOT_PRIVATE);
    }
    if (message.senderBot()) {
      return TelegramInboundDecision.ignored(TelegramInboundDecision.IgnoreReason.BOT_SENDER);
    }
    if (message.chatId() <= 0 || message.senderId() <= 0 || message.messageId() <= 0) {
      return TelegramInboundDecision.ignored(TelegramInboundDecision.IgnoreReason.INVALID_ID);
    }
    if (message.chatId() != message.senderId()) {
      return TelegramInboundDecision.ignored(
          TelegramInboundDecision.IgnoreReason.IDENTITY_MISMATCH);
    }
    if (!allowedUserIds.contains(message.senderId())) {
      return TelegramInboundDecision.ignored(TelegramInboundDecision.IgnoreReason.NOT_ALLOWED);
    }
    if (message.occurredAt() == null) {
      return TelegramInboundDecision.ignored(TelegramInboundDecision.IgnoreReason.INVALID_TIME);
    }
    if (message.text() == null) {
      return TelegramInboundDecision.ignored(
          TelegramInboundDecision.IgnoreReason.UNSUPPORTED_CONTENT);
    }
    String content = message.text().strip();
    if (content.isEmpty()) {
      return TelegramInboundDecision.ignored(TelegramInboundDecision.IgnoreReason.BLANK_TEXT);
    }
    if (content.codePointCount(0, content.length()) > MessageContract.MAX_CONTENT_CHARACTERS) {
      return TelegramInboundDecision.ignored(TelegramInboundDecision.IgnoreReason.CONTENT_TOO_LONG);
    }
    if (content.equals("/cancel") || content.equals("/stop")) {
      return TelegramInboundDecision.control(TelegramInboundDecision.Control.CANCEL);
    }

    String externalId = Long.toString(message.chatId());
    return TelegramInboundDecision.accepted(
        new InboundMessage(
            MessageContract.CURRENT_VERSION,
            CHANNEL + ":" + externalId + ":" + message.messageId(),
            ids.newTurnId(),
            CHANNEL + ":" + externalId,
            new MessageRoute(CHANNEL, externalId),
            Long.toString(message.senderId()),
            content,
            message.occurredAt()));
  }
}
