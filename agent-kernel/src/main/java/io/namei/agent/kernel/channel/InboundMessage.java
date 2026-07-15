package io.namei.agent.kernel.channel;

import java.time.Instant;

public record InboundMessage(
    int schemaVersion,
    String messageId,
    String turnId,
    String sessionId,
    MessageRoute route,
    String senderId,
    String content,
    Instant occurredAt) {
  public InboundMessage {
    MessageContract.requireCurrentVersion(schemaVersion);
    messageId =
        MessageContract.identifier(
            messageId, "messageId", MessageContract.MAX_MESSAGE_ID_CHARACTERS);
    turnId =
        MessageContract.identifier(turnId, "turnId", MessageContract.MAX_TURN_ID_CHARACTERS);
    sessionId =
        MessageContract.identifier(
            sessionId, "sessionId", MessageContract.MAX_SESSION_ID_CHARACTERS);
    if (route == null) {
      throw new IllegalArgumentException("route 不能为空");
    }
    senderId =
        MessageContract.identifier(
            senderId, "senderId", MessageContract.MAX_SENDER_ID_CHARACTERS);
    content = MessageContract.inboundContent(content);
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurredAt 不能为空");
    }
  }

  @Override
  public String toString() {
    return "InboundMessage[schemaVersion=" + schemaVersion + ", sensitiveFields=<redacted>]";
  }
}
