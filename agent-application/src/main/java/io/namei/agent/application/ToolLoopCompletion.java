package io.namei.agent.application;

import java.util.Objects;

/** Internal distinction between an ordinary model final and a durably committed Pending Turn. */
sealed interface ToolLoopCompletion permits ToolLoopCompletion.Final, ToolLoopCompletion.Pending {
  record Final(String content) implements ToolLoopCompletion {
    public Final {
      content = required(content, "最终文本");
    }
  }

  record Pending(String assistantProjection) implements ToolLoopCompletion {
    public Pending {
      assistantProjection = required(assistantProjection, "Pending 投影");
    }
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return normalized;
  }
}
