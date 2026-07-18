package io.namei.agent.application;

import io.namei.agent.kernel.evidence.ConversationEvidenceMessage;
import io.namei.agent.kernel.evidence.ConversationEvidencePage;
import io.namei.agent.kernel.evidence.ConversationEvidenceReference;
import io.namei.agent.kernel.evidence.ConversationEvidenceSearchQuery;
import java.util.List;

/** Internal, already-bound scope exposed only through {@link ToolInvocationContext}. */
interface ConversationEvidenceScope {
  List<ConversationEvidenceMessage> fetch(List<ConversationEvidenceReference> references);

  List<ConversationEvidenceMessage> fetchWindow(
      List<ConversationEvidenceReference> references, int context);

  ConversationEvidencePage search(ConversationEvidenceSearchQuery query);
}
