package io.namei.agent.kernel.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChatMessageTest {
  @Test
  void trimsContent() {
    assertThat(new ChatMessage(MessageRole.USER, "  你好  ").content()).isEqualTo("你好");
  }

  @Test
  void rejectsBlankContent() {
    assertThatThrownBy(() -> new ChatMessage(MessageRole.USER, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("消息内容不能为空");
  }
}
