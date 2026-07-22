package io.namei.agent.kernel.channel;

/**
 * 表示渠道消息的投递目标。
 *
 * @param channel 渠道类型，例如 {@code telegram}
 * @param conversationId 渠道内部会话标识；属于敏感路由信息，不会出现在 {@link #toString()} 中
 */
public record MessageRoute(String channel, String conversationId) {
  public MessageRoute {
    channel = MessageContract.channel(channel);
    conversationId =
        MessageContract.identifier(
            conversationId, "conversationId", MessageContract.MAX_ROUTE_ID_CHARACTERS);
  }

  @Override
  public String toString() {
    return "MessageRoute[sensitiveFields=<redacted>]";
  }
}
