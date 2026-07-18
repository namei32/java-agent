package io.namei.agent.application;

import io.namei.agent.kernel.model.ModelMessage;
import io.namei.agent.kernel.prompt.PromptSectionId;
import io.namei.agent.kernel.prompt.PromptTrimPlan;
import java.util.List;
import java.util.Objects;

public record PromptAssembly(
    List<ModelMessage> messages,
    String systemPrompt,
    String contextFrame,
    List<PromptSectionId> systemSections,
    List<PromptSectionId> frameSections,
    PromptTrimPlan trimPlan,
    List<PromptSectionId> trimmedSections,
    int systemTokens,
    int frameTokens,
    int totalTokens) {
  public PromptAssembly {
    messages = List.copyOf(messages);
    Objects.requireNonNull(systemPrompt, "systemPrompt");
    Objects.requireNonNull(contextFrame, "contextFrame");
    systemSections = List.copyOf(systemSections);
    frameSections = List.copyOf(frameSections);
    Objects.requireNonNull(trimPlan, "trimPlan");
    trimmedSections = List.copyOf(trimmedSections);
    if (systemTokens < 0 || frameTokens < 0 || totalTokens < systemTokens + frameTokens) {
      throw new IllegalArgumentException("Prompt token 计数无效");
    }
  }
}
