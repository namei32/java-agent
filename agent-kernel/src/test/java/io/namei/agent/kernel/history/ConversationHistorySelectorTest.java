package io.namei.agent.kernel.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationHistorySelectorTest {
  private final ConversationHistorySelector selector = new ConversationHistorySelector();

  @Test
  void keepsNewestCompleteTurnsWithinBothLimits() {
    var history =
        List.of(
            new ChatMessage(MessageRole.ASSISTANT, "孤立响应"),
            new ChatMessage(MessageRole.USER, "旧问题123"),
            new ChatMessage(MessageRole.ASSISTANT, "旧回答123"),
            new ChatMessage(MessageRole.USER, "新问题"),
            new ChatMessage(MessageRole.ASSISTANT, "新回答"));

    assertThat(selector.select(history, new HistoryLimits(4, 6)))
        .containsExactly(
            new ChatMessage(MessageRole.USER, "新问题"),
            new ChatMessage(MessageRole.ASSISTANT, "新回答"));
  }

  @Test
  void neverReturnsAnOrphanAssistantMessage() {
    assertThat(
            selector.select(
                List.of(new ChatMessage(MessageRole.ASSISTANT, "孤立响应")),
                new HistoryLimits(40, 100_000)))
        .isEmpty();
  }

  @Test
  void keepsOnlyNewestTurnWhenMessageLimitExcludesOlderTurn() {
    var history =
        List.of(
            new ChatMessage(MessageRole.USER, "旧问题"),
            new ChatMessage(MessageRole.ASSISTANT, "旧回答"),
            new ChatMessage(MessageRole.USER, "新问题"),
            new ChatMessage(MessageRole.ASSISTANT, "新回答"));

    assertThat(selector.select(history, new HistoryLimits(2, 100_000)))
        .containsExactly(
            new ChatMessage(MessageRole.USER, "新问题"),
            new ChatMessage(MessageRole.ASSISTANT, "新回答"));
  }

  @Test
  void rejectsNegativeMaxMessages() {
    assertThatThrownBy(() -> new HistoryLimits(-1, 100_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("历史窗口限制不能为负数");
  }

  @Test
  void rejectsNegativeMaxCharacters() {
    assertThatThrownBy(() -> new HistoryLimits(40, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("历史窗口限制不能为负数");
  }
}
