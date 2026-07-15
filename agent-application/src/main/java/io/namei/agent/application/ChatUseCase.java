package io.namei.agent.application;

@FunctionalInterface
public interface ChatUseCase {
  ChatResult chat(ChatCommand command);

  default ChatResult chat(ChatCommand command, TurnCancellation cancellation) {
    if (cancellation == null) {
      throw new IllegalArgumentException("cancellation 不能为空");
    }
    return chat(command);
  }
}
