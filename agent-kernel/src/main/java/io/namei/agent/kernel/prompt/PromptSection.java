package io.namei.agent.kernel.prompt;

import java.util.Objects;

public record PromptSection(PromptSectionId id, PromptPlacement placement, String content) {
  public PromptSection {
    id = Objects.requireNonNull(id, "id");
    placement = Objects.requireNonNull(placement, "placement");
    if (placement != id.placement() || content == null || content.strip().isEmpty()) {
      throw PromptContract.violation(PromptStableCode.PROMPT_CONTRACT_INVALID);
    }
    content = content.strip();
  }
}
