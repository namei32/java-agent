package io.namei.agent.bootstrap.telegram;

@FunctionalInterface
public interface TelegramIdGenerator {
  String newTurnId();
}
