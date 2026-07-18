package io.namei.agent.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, explicit per-invocation context. It deliberately carries no raw session identifier.
 */
public final class ToolInvocationContext {
  private static final ToolInvocationContext NONE = new ToolInvocationContext(null);

  private final ConversationEvidenceScope evidenceScope;

  private ToolInvocationContext(ConversationEvidenceScope evidenceScope) {
    this.evidenceScope = evidenceScope;
  }

  public static ToolInvocationContext none() {
    return NONE;
  }

  static ToolInvocationContext withConversationEvidence(ConversationEvidenceScope evidenceScope) {
    return new ToolInvocationContext(Objects.requireNonNull(evidenceScope, "evidenceScope"));
  }

  Optional<ConversationEvidenceScope> conversationEvidenceScope() {
    return Optional.ofNullable(evidenceScope);
  }
}
