package io.namei.agent.application;

import java.util.Objects;
import java.util.Optional;

/** 每次调用显式提供的不可变上下文；它有意不携带原始 Session 标识符。 */
public final class ToolInvocationContext {
  private static final ToolInvocationContext NONE = new ToolInvocationContext(null, null);

  private final ConversationEvidenceScope evidenceScope;
  private final MemoryRecallScope memoryRecallScope;

  private ToolInvocationContext(
      ConversationEvidenceScope evidenceScope, MemoryRecallScope memoryRecallScope) {
    this.evidenceScope = evidenceScope;
    this.memoryRecallScope = memoryRecallScope;
  }

  public static ToolInvocationContext none() {
    return NONE;
  }

  static ToolInvocationContext withConversationEvidence(ConversationEvidenceScope evidenceScope) {
    return new ToolInvocationContext(Objects.requireNonNull(evidenceScope, "evidenceScope"), null);
  }

  ToolInvocationContext withMemoryRecall(MemoryRecallScope memoryRecallScope) {
    return new ToolInvocationContext(
        evidenceScope, Objects.requireNonNull(memoryRecallScope, "memoryRecallScope"));
  }

  Optional<ConversationEvidenceScope> conversationEvidenceScope() {
    return Optional.ofNullable(evidenceScope);
  }

  Optional<MemoryRecallScope> memoryRecallScope() {
    return Optional.ofNullable(memoryRecallScope);
  }
}
