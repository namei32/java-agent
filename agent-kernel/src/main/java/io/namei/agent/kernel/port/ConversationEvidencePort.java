package io.namei.agent.kernel.port;

import io.namei.agent.kernel.evidence.ConversationEvidenceMessage;
import io.namei.agent.kernel.evidence.ConversationEvidencePage;
import io.namei.agent.kernel.evidence.ConversationEvidenceReference;
import io.namei.agent.kernel.evidence.ConversationEvidenceSearchQuery;
import java.util.List;
import java.util.Objects;

/**
 * Read-only access to evidence already persisted for one conversation session.
 *
 * <p>Callers must bind the private session key before the port reaches a model-facing tool. The
 * port does not create, update, delete, or migrate data.
 */
public interface ConversationEvidencePort {
  List<ConversationEvidenceMessage> fetch(
      String sessionId, List<ConversationEvidenceReference> references);

  List<ConversationEvidenceMessage> fetchWindow(
      String sessionId, List<ConversationEvidenceReference> sourceReferences, int context);

  ConversationEvidencePage search(String sessionId, ConversationEvidenceSearchQuery query);

  static ConversationEvidencePort disabled() {
    return DisabledConversationEvidencePort.INSTANCE;
  }

  enum DisabledConversationEvidencePort implements ConversationEvidencePort {
    INSTANCE;

    @Override
    public List<ConversationEvidenceMessage> fetch(
        String sessionId, List<ConversationEvidenceReference> references) {
      unavailable(sessionId, references);
      return List.of();
    }

    @Override
    public List<ConversationEvidenceMessage> fetchWindow(
        String sessionId, List<ConversationEvidenceReference> sourceReferences, int context) {
      unavailable(sessionId, sourceReferences);
      return List.of();
    }

    @Override
    public ConversationEvidencePage search(
        String sessionId, ConversationEvidenceSearchQuery query) {
      unavailable(sessionId, query);
      return new ConversationEvidencePage(List.of(), 0, false);
    }

    private static void unavailable(Object first, Object second) {
      Objects.requireNonNull(first, "sessionId");
      Objects.requireNonNull(second, "request");
      throw new IllegalStateException("会话证据读取未启用");
    }
  }
}
