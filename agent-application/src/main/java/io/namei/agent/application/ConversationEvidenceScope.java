package io.namei.agent.application;

import io.namei.agent.kernel.evidence.ConversationEvidenceMessage;
import io.namei.agent.kernel.evidence.ConversationEvidencePage;
import io.namei.agent.kernel.evidence.ConversationEvidenceReference;
import io.namei.agent.kernel.evidence.ConversationEvidenceSearchQuery;
import java.util.List;

/** 仅通过 {@link ToolInvocationContext} 暴露的内部已绑定 Scope。 */
interface ConversationEvidenceScope {
  List<ConversationEvidenceMessage> fetch(List<ConversationEvidenceReference> references);

  List<ConversationEvidenceMessage> fetchWindow(
      List<ConversationEvidenceReference> references, int context);

  ConversationEvidencePage search(ConversationEvidenceSearchQuery query);
}
