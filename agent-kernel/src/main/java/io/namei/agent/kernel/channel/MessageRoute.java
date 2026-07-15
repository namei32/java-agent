package io.namei.agent.kernel.channel;

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
