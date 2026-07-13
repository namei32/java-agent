package io.namei.agent.application;

@FunctionalInterface
public interface ChatUseCase {
  ChatResult chat(ChatCommand command);
}
