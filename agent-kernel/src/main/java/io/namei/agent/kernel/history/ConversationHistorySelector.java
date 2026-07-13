package io.namei.agent.kernel.history;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConversationHistorySelector {
  public List<ChatMessage> select(List<ChatMessage> history, HistoryLimits limits) {
    var selectedReversed = new ArrayList<ChatMessage>();
    int characters = 0;
    int index = history.size() - 1;
    while (index > 0) {
      ChatMessage assistant = history.get(index);
      ChatMessage user = history.get(index - 1);
      if (assistant.role() != MessageRole.ASSISTANT || user.role() != MessageRole.USER) {
        index--;
        continue;
      }
      int pairCharacters = user.content().length() + assistant.content().length();
      if (selectedReversed.size() + 2 > limits.maxMessages()
          || characters + pairCharacters > limits.maxCharacters()) {
        break;
      }
      selectedReversed.add(assistant);
      selectedReversed.add(user);
      characters += pairCharacters;
      index -= 2;
    }
    Collections.reverse(selectedReversed);
    return List.copyOf(selectedReversed);
  }
}
