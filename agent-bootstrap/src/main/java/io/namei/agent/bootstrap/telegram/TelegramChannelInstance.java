package io.namei.agent.bootstrap.telegram;

import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import java.util.Objects;

public record TelegramChannelInstance(ChannelInstanceId id) {
  public TelegramChannelInstance {
    Objects.requireNonNull(id, "id");
    if (!"telegram".equals(id.channel())) {
      throw new IllegalArgumentException("Telegram Instance 渠道不匹配");
    }
  }

  public static TelegramChannelInstance from(TelegramBotToken token) {
    Objects.requireNonNull(token, "token");
    return new TelegramChannelInstance(ChannelInstanceId.derive("telegram", token.botId()));
  }

  @Override
  public String toString() {
    return "TelegramChannelInstance[id=<redacted>]";
  }
}
