package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import org.junit.jupiter.api.Test;

class ChatResultTest {
  @Test
  void rejectsNullSessionId() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ChatResult(null, assistant()))
        .withMessage("sessionId");
  }

  @Test
  void rejectsNullAssistant() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ChatResult("session", null))
        .withMessage("assistant");
  }

  @Test
  void rejectsNonAssistantMessage() {
    assertThatThrownBy(
            () -> new ChatResult("session", new ChatMessage(MessageRole.USER, "not assistant")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("assistant 必须使用 ASSISTANT 角色");
  }

  private static ChatMessage assistant() {
    return new ChatMessage(MessageRole.ASSISTANT, "answer");
  }
}
