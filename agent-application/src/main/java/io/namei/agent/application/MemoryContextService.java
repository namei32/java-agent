package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryProfile;
import io.namei.agent.kernel.memory.MemoryRetrievalRequest;
import io.namei.agent.kernel.memory.MemoryRetrievalResult;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.port.MemoryProfilePort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.prompt.PromptMode;
import io.namei.agent.kernel.prompt.PromptSection;
import io.namei.agent.kernel.prompt.PromptSectionId;
import io.namei.agent.kernel.prompt.PromptTrimPlan;
import io.namei.agent.kernel.prompt.PromptTurnContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class MemoryContextService {
  private static final int DEFAULT_MAX_CONTEXT_CHARACTERS = 100_000;
  private static final int DEFAULT_MAX_RETRIEVED_CHARACTERS = 20_000;

  private final MemoryProfilePort profiles;
  private final MemoryRetrievalPort retrieval;
  private final int maxContextCharacters;
  private final int maxRetrievedCharacters;
  private final ContextAssembler assembler;
  private final PromptRuntimeSettings promptSettings;
  private final AkashicCorePromptRenderer coreRenderer;
  private final PromptOrchestrator promptOrchestrator;
  private final SkillPromptService skillPrompts;

  public MemoryContextService(
      MemoryProfilePort profiles,
      MemoryRetrievalPort retrieval,
      int maxContextCharacters,
      int maxRetrievedCharacters) {
    this(
        profiles,
        retrieval,
        maxContextCharacters,
        maxRetrievedCharacters,
        PromptRuntimeSettings.minimalDefaults(),
        null,
        SkillPromptService.disabled());
  }

  public MemoryContextService(
      MemoryProfilePort profiles,
      MemoryRetrievalPort retrieval,
      int maxContextCharacters,
      int maxRetrievedCharacters,
      PromptRuntimeSettings promptSettings,
      AkashicCorePromptRenderer coreRenderer) {
    this(
        profiles,
        retrieval,
        maxContextCharacters,
        maxRetrievedCharacters,
        promptSettings,
        coreRenderer,
        SkillPromptService.disabled());
  }

  public MemoryContextService(
      MemoryProfilePort profiles,
      MemoryRetrievalPort retrieval,
      int maxContextCharacters,
      int maxRetrievedCharacters,
      PromptRuntimeSettings promptSettings,
      AkashicCorePromptRenderer coreRenderer,
      SkillPromptService skillPrompts) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
    if (maxContextCharacters < 1 || maxRetrievedCharacters < 1) {
      throw new IllegalArgumentException("记忆上下文上限必须大于零");
    }
    this.maxContextCharacters = maxContextCharacters;
    this.maxRetrievedCharacters = maxRetrievedCharacters;
    this.assembler = new ContextAssembler();
    this.promptSettings = Objects.requireNonNull(promptSettings, "promptSettings");
    if (promptSettings.mode() == PromptMode.AKASHIC_CORE && coreRenderer == null) {
      throw new IllegalArgumentException("AKASHIC_CORE 需要 classpath Persona");
    }
    this.coreRenderer = coreRenderer;
    this.promptOrchestrator = new PromptOrchestrator();
    this.skillPrompts = Objects.requireNonNull(skillPrompts, "skillPrompts");
  }

  public static MemoryContextService disabled() {
    return new MemoryContextService(
        MemoryProfilePort.empty(),
        MemoryRetrievalPort.disabled(),
        DEFAULT_MAX_CONTEXT_CHARACTERS,
        DEFAULT_MAX_RETRIEVED_CHARACTERS);
  }

  ContextAssembler.AssembledContext assemble(
      String basePrompt,
      String sessionBinding,
      List<ChatMessage> fullHistory,
      List<ChatMessage> selectedHistory,
      ChatMessage currentUser,
      Instant requestedAt) {
    return assemble(
        basePrompt,
        sessionBinding,
        sessionBinding,
        fullHistory,
        selectedHistory,
        currentUser,
        requestedAt,
        null,
        PromptTrimPlan.FULL);
  }

  ContextAssembler.AssembledContext assemble(
      String basePrompt,
      String sessionBinding,
      String sessionId,
      List<ChatMessage> fullHistory,
      List<ChatMessage> selectedHistory,
      ChatMessage currentUser,
      Instant requestedAt,
      PromptTurnContext turnContext) {
    return assemble(
        basePrompt,
        sessionBinding,
        sessionId,
        fullHistory,
        selectedHistory,
        currentUser,
        requestedAt,
        turnContext,
        PromptTrimPlan.FULL);
  }

  ContextAssembler.AssembledContext assemble(
      String basePrompt,
      String sessionBinding,
      String sessionId,
      List<ChatMessage> fullHistory,
      List<ChatMessage> selectedHistory,
      ChatMessage currentUser,
      Instant requestedAt,
      PromptTurnContext turnContext,
      PromptTrimPlan minimumPlan) {
    Objects.requireNonNull(minimumPlan, "minimumPlan");
    MemoryProfile profile = loadProfile();
    MemoryRetrievalResult result =
        retrieve(sessionBinding, fullHistory, currentUser.content(), requestedAt);
    if (promptSettings.mode() == PromptMode.AKASHIC_CORE) {
      return assembleAkashicCore(
          profile,
          result.block(),
          selectedHistory,
          currentUser,
          requestedAt,
          sessionId,
          turnContext,
          minimumPlan);
    }
    return assembler.assemble(
        basePrompt, profile, selectedHistory, result.block(), Set.of(), currentUser);
  }

  private ContextAssembler.AssembledContext assembleAkashicCore(
      MemoryProfile profile,
      String retrievedMemory,
      List<ChatMessage> selectedHistory,
      ChatMessage currentUser,
      Instant requestedAt,
      String sessionId,
      PromptTurnContext suppliedContext,
      PromptTrimPlan minimumPlan) {
    PromptTurnContext turnContext =
        suppliedContext == null
            ? new PromptTurnContext(requestedAt, promptSettings.zoneId(), "unknown", sessionId)
            : new PromptTurnContext(
                suppliedContext.requestTime(),
                promptSettings.zoneId(),
                suppliedContext.channel(),
                suppliedContext.sessionId());
    var sections =
        new ArrayList<PromptSection>(coreRenderer.render(promptSettings.mode(), turnContext));
    SkillPromptSections skills = skillPrompts.render();
    addSection(sections, PromptSectionId.SKILLS_CATALOG, skills.catalog());
    addSection(sections, PromptSectionId.SELF_MODEL, "## Akashic 自我认知\n\n" + profile.selfModel());
    addSection(
        sections,
        PromptSectionId.LONG_TERM_MEMORY,
        "## Long-term Memory\n" + profile.longTermMemory());
    addSection(sections, PromptSectionId.RECENT_CONTEXT, trimRecentTurns(profile.recentContext()));
    addSection(sections, PromptSectionId.ACTIVE_SKILLS, skills.active());
    addSection(sections, PromptSectionId.RETRIEVED_MEMORY, retrievedMemory);

    PromptAssembly assembled =
        promptOrchestrator.assemble(
            sections, selectedHistory, currentUser, promptSettings.budget(), minimumPlan);
    var sectionNames = new ArrayList<String>();
    assembled.systemSections().forEach(section -> sectionNames.add(section.value()));
    assembled.frameSections().forEach(section -> sectionNames.add(section.value()));
    return new ContextAssembler.AssembledContext(
        assembled.messages(), assembled.systemPrompt(), sectionNames, assembled.contextFrame());
  }

  private static void addSection(List<PromptSection> sections, PromptSectionId id, String content) {
    if (content != null && !content.strip().isEmpty()) {
      sections.add(new PromptSection(id, id.placement(), content));
    }
  }

  private static String trimRecentTurns(String content) {
    int marker = content.indexOf("\n## Recent Turns");
    return (marker < 0 ? content : content.substring(0, marker)).strip();
  }

  private MemoryProfile loadProfile() {
    try {
      MemoryProfile profile = Objects.requireNonNull(profiles.load(), "profile");
      long totalCharacters =
          (long) profile.selfModel().length()
              + profile.longTermMemory().length()
              + profile.recentContext().length();
      if (totalCharacters > maxContextCharacters) {
        throw new MemoryContextUnavailableException();
      }
      return profile;
    } catch (MemoryContextUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new MemoryContextUnavailableException();
    }
  }

  private MemoryRetrievalResult retrieve(
      String sessionBinding,
      List<ChatMessage> fullHistory,
      String currentMessage,
      Instant requestedAt) {
    try {
      var request =
          new MemoryRetrievalRequest(sessionBinding, currentMessage, fullHistory, requestedAt);
      MemoryRetrievalResult result =
          Objects.requireNonNull(retrieval.retrieve(request), "retrieval result");
      if (result.block().length() > maxRetrievedCharacters) {
        throw new MemoryContextUnavailableException();
      }
      return result;
    } catch (MemoryContextUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new MemoryContextUnavailableException();
    }
  }
}
