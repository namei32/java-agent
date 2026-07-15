package io.namei.agent.kernel.memory;

import java.util.List;
import java.util.Objects;

public record EmbeddingRequest(List<String> texts) {
  public EmbeddingRequest {
    texts = List.copyOf(Objects.requireNonNull(texts, "texts"));
    for (String text : texts) {
      Objects.requireNonNull(text, "embedding text");
      if (text.strip().isBlank()) {
        throw new IllegalArgumentException("Embedding Text 不能为空");
      }
    }
  }

  @Override
  public String toString() {
    return "EmbeddingRequest[count=" + texts.size() + "]";
  }
}
