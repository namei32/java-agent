package io.namei.agent.kernel.evidence;

import java.util.Locale;
import java.util.Optional;

/** Only persisted user and assistant content can become model-visible conversation evidence. */
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
