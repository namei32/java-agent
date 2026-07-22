package io.namei.agent.kernel.channel;

import java.time.Instant;

/**
 * 表示外部渠道进入 Agent 的标准化消息。
 *
 * <p>渠道适配器负责把 Telegram 等供应商事件转换为该结构；核心层只依赖此协议。所有标识、正文长度和协议版本都会在构造时校验，敏感字段不会由 {@link #toString()}
 * 输出。
 *
 * @param schemaVersion 消息协议版本
 * @param messageId 外部消息的稳定标识，用于去重
 * @param turnId Agent 轮次标识；可靠渠道可在接收后重新分配
 * @param sessionId 映射后的内部会话标识
 * @param route 原始渠道投递路由
 * @param senderId 外部发送者标识
 * @param content 用户输入正文
 * @param occurredAt 外部事件发生时间
 */
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
    turnId = MessageContract.identifier(turnId, "turnId", MessageContract.MAX_TURN_ID_CHARACTERS);
    sessionId =
        MessageContract.identifier(
            sessionId, "sessionId", MessageContract.MAX_SESSION_ID_CHARACTERS);
    if (route == null) {
      throw new IllegalArgumentException("route 不能为空");
    }
    senderId =
        MessageContract.identifier(senderId, "senderId", MessageContract.MAX_SENDER_ID_CHARACTERS);
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
