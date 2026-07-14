package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryProfile;
import io.namei.agent.kernel.memory.MemoryRetrievalRequest;
import io.namei.agent.kernel.memory.MemoryRetrievalResult;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.port.MemoryProfilePort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import java.time.Instant;
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

  public MemoryContextService(
      MemoryProfilePort profiles,
      MemoryRetrievalPort retrieval,
      int maxContextCharacters,
      int maxRetrievedCharacters) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
    if (maxContextCharacters < 1 || maxRetrievedCharacters < 1) {
      throw new IllegalArgumentException("记忆上下文上限必须大于零");
    }
    this.maxContextCharacters = maxContextCharacters;
    this.maxRetrievedCharacters = maxRetrievedCharacters;
    this.assembler = new ContextAssembler();
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
    MemoryProfile profile = loadProfile();
    MemoryRetrievalResult result =
        retrieve(sessionBinding, fullHistory, currentUser.content(), requestedAt);
    return assembler.assemble(
        basePrompt, profile, selectedHistory, result.block(), Set.of(), currentUser);
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
