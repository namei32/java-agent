package io.namei.agent.kernel.history;

public record HistoryLimits(int maxMessages, int maxCharacters) {
  public HistoryLimits {
    if (maxMessages < 0 || maxCharacters < 0) {
      throw new IllegalArgumentException("历史窗口限制不能为负数");
    }
  }
}
