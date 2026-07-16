package io.namei.agent.bootstrap.telegram;

import java.time.Duration;
import java.util.List;

public interface TelegramBotApi {
  List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout);

  TelegramSendReceipt sendMessage(long chatId, String text);
}
