package io.namei.agent.kernel.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationEvidenceModelTest {
  @Test
  void opaqueReferenceAcceptsOnlyCanonicalCurrentSessionForm() {
    assertThat(ConversationEvidenceReference.parse("msg-v1:12"))
        .contains(new ConversationEvidenceReference(12));
    assertThat(ConversationEvidenceReference.parse("msg-v1:0"))
        .contains(new ConversationEvidenceReference(0));
    assertThat(ConversationEvidenceReference.parse("msg-v1:012")).isEmpty();
    assertThat(ConversationEvidenceReference.parse("telegram:123:12")).isEmpty();
    assertThat(ConversationEvidenceReference.parse("msg-v1:-1")).isEmpty();
    assertThat(ConversationEvidenceReference.parse("msg-v1:12 ")).isEmpty();
  }

  @Test
  void evidenceModelsRejectInvalidContentAndPagination() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ConversationEvidenceMessage(
                    new ConversationEvidenceReference(1), ConversationEvidenceRole.USER, "  "));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ConversationEvidenceSearchQuery(
                    List.of("cache", "cache"), Optional.empty(), 10, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new ConversationEvidenceSearchQuery(List.of("cache"), Optional.empty(), 51, 0));
  }
}
