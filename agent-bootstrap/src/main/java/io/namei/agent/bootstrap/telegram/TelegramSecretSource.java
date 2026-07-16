package io.namei.agent.bootstrap.telegram;

@FunctionalInterface
public interface TelegramSecretSource {
  String readToken();
}
