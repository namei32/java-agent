package io.namei.agent.bootstrap.telegram;

import java.util.ArrayList;
import java.util.List;

public final class TelegramTextChunker {
  public static final int MAX_CHUNK_UNITS = 4000;

  public List<String> split(String text) {
    if (text == null || text.isEmpty()) {
      throw new IllegalArgumentException("Telegram 分片文本不能为空");
    }
    var chunks = new ArrayList<String>((text.length() + MAX_CHUNK_UNITS - 1) / MAX_CHUNK_UNITS);
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(start + MAX_CHUNK_UNITS, text.length());
      if (end < text.length()
          && Character.isHighSurrogate(text.charAt(end - 1))
          && Character.isLowSurrogate(text.charAt(end))) {
        end--;
      }
      chunks.add(text.substring(start, end));
      start = end;
    }
    return List.copyOf(chunks);
  }
}
