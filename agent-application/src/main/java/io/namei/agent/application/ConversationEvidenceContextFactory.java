package io.namei.agent.application;

import io.namei.agent.kernel.evidence.ConversationEvidenceMessage;
import io.namei.agent.kernel.evidence.ConversationEvidencePage;
import io.namei.agent.kernel.evidence.ConversationEvidenceReference;
import io.namei.agent.kernel.evidence.ConversationEvidenceSearchQuery;
import io.namei.agent.kernel.port.ConversationEvidencePort;
import java.util.List;
import java.util.Objects;

/** Creates an opaque Tool invocation context after ChatService has authenticated the session. */
public final class ConversationEvidenceContextFactory {
  private static final ConversationEvidenceContextFactory DISABLED =
      new ConversationEvidenceContextFactory(ConversationEvidencePort.disabled(), false);

  private final ConversationEvidencePort port;
  private final boolean enabled;

  private ConversationEvidenceContextFactory(ConversationEvidencePort port, boolean enabled) {
    this.port = Objects.requireNonNull(port, "port");
    this.enabled = enabled;
  }

  public static ConversationEvidenceContextFactory enabled(ConversationEvidencePort port) {
    return new ConversationEvidenceContextFactory(port, true);
  }

  public static ConversationEvidenceContextFactory disabled() {
    return DISABLED;
  }

  public ToolInvocationContext forSession(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    if (!enabled) {
      return ToolInvocationContext.none();
    }
    return ToolInvocationContext.withConversationEvidence(new BoundScope(port, sessionId));
  }

  private record BoundScope(ConversationEvidencePort port, String sessionId)
      implements ConversationEvidenceScope {
    private BoundScope {
      Objects.requireNonNull(port, "port");
      Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override
    public List<ConversationEvidenceMessage> fetch(List<ConversationEvidenceReference> references) {
      return port.fetch(sessionId, references);
    }

    @Override
    public List<ConversationEvidenceMessage> fetchWindow(
        List<ConversationEvidenceReference> references, int context) {
      return port.fetchWindow(sessionId, references, context);
    }

    @Override
    public ConversationEvidencePage search(ConversationEvidenceSearchQuery query) {
      return port.search(sessionId, query);
    }
  }
}
