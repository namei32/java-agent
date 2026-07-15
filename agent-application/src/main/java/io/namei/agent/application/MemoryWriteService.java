package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.memory.MemoryWriteReplayQuery;
import io.namei.agent.kernel.memory.MemoryWriteResult;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryWritePort;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MemoryWriteService {
  private final EmbeddingPort embeddings;
  private final MemoryWritePort writer;
  private final MemoryItemIdGenerator ids;
  private final Clock clock;

  public MemoryWriteService(
      EmbeddingPort embeddings, MemoryWritePort writer, MemoryItemIdGenerator ids, Clock clock) {
    this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public MemoryWriteOutcome write(String sessionId, MemoryWriteRequest request) {
    Objects.requireNonNull(request, "request");
    var scope = MemoryManagementRules.scope(sessionId);
    String contentHash = MemoryManagementRules.contentHash(request.content());
    String argumentHash =
        MemoryManagementRules.writeArgumentHash(
            request.type(), contentHash, request.emotionalWeight(), request.happenedAt());
    var replayQuery =
        new MemoryWriteReplayQuery(new MemoryMutationKey(scope, request.requestId()), argumentHash);
    Optional<MemoryWriteResult> replay =
        Objects.requireNonNull(writer.replayUpsert(replayQuery), "replay");
    if (replay.isPresent()) {
      return outcome(replay.orElseThrow());
    }

    EmbeddingResult embedding = embeddings.embed(new EmbeddingRequest(List.of(request.content())));
    if (embedding == null || embedding.vectors().size() != 1) {
      throw new InvalidEmbeddingResponseException();
    }

    var command =
        new MemoryWriteCommand(
            scope,
            request.requestId(),
            ids.newMemoryItemId(),
            request.type(),
            request.content(),
            contentHash,
            embedding.vectors().getFirst(),
            embedding.model(),
            request.emotionalWeight(),
            MemorySourceKind.EXPLICIT_API,
            request.happenedAt(),
            argumentHash,
            clock.instant());
    return outcome(Objects.requireNonNull(writer.upsert(command), "write result"));
  }

  private static MemoryWriteOutcome outcome(MemoryWriteResult result) {
    return new MemoryWriteOutcome(result.status(), MemoryView.from(result.item()));
  }
}
