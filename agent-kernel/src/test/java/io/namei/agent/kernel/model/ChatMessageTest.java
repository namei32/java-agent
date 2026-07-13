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

  @Test
  void stripsUnicodeWhitespace() {
    assertThat(new ChatMessage(MessageRole.USER, "\u2003你好\u2003").content()).isEqualTo("你好");
  }

  @Test
  void rejectsUnicodeBlankContent() {
    assertThatThrownBy(() -> new ChatMessage(MessageRole.USER, "\u2003"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("消息内容不能为空");
  }

  @Test
  void rejectsNullRole() {
    assertThatThrownBy(() -> new ChatMessage(null, "你好"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("role");
  }

  @Test
  void rejectsNullContent() {
    assertThatThrownBy(() -> new ChatMessage(MessageRole.USER, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("content");
  }
}
