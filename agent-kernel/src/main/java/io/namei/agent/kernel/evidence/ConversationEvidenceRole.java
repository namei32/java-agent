package io.namei.agent.kernel.evidence;

import java.util.Locale;
import java.util.Optional;

/** 只有已持久化的 User 与 Assistant 内容可以成为模型可见的会话证据。 */
public enum ConversationEvidenceRole {
  USER,
  ASSISTANT;

  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Optional<ConversationEvidenceRole> fromWireValue(String value) {
    if (value == null) {
      return Optional.empty();
    }
    return switch (value) {
      case "user" -> Optional.of(USER);
      case "assistant" -> Optional.of(ASSISTANT);
      default -> Optional.empty();
    };
  }
}
