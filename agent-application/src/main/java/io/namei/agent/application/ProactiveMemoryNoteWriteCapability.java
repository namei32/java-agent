package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.memory.MemoryWriteReplayQuery;
import io.namei.agent.kernel.memory.MemoryWriteResult;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryWritePort;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * P6 policy constants only. Execution is added separately and remains unconnected to Bootstrap,
 * tools, workers, and runtime configuration.
 */
final class ProactiveMemoryNoteWriteCapability {
  static final String CAPABILITY_NAME = "proactive_memory_note_write";
  static final String CAPABILITY_VERSION = "r14-proactive-memory-note-v1";
  static final String FIXED_EMBEDDING_MODEL = "fake-r14-p6-memory-v1";

  private final EmbeddingPort embeddings;
  private final MemoryWritePort writer;
  private final MemoryItemIdGenerator ids;
  private final Clock clock;

  ProactiveMemoryNoteWriteCapability(
      EmbeddingPort embeddings, MemoryWritePort writer, MemoryItemIdGenerator ids, Clock clock) {
    this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  static MemoryType memoryType() {
    return MemoryType.NOTE;
  }

  static int emotionalWeight() {
    return 0;
  }

  static MemorySourceKind sourceKind() {
    return MemorySourceKind.PROACTIVE_APPROVED;
  }

  static String requestIdFor(ProactiveMemoryNoteWriteOperationReference reference) {
    return "p6-note-" + Objects.requireNonNull(reference, "reference").value();
  }

  ProactiveMemoryNoteWriteSafeReceipt write(
      ProactiveMemoryNoteWriteOperation operation, ProactiveMemoryNoteWriteCapsule capsule) {
    return tryWrite(operation, capsule)
        .orElseThrow(() -> new IllegalStateException("P6 NOTE 写入尚未获准消费"));
  }

  Optional<ProactiveMemoryNoteWriteSafeReceipt> tryWrite(
      ProactiveMemoryNoteWriteOperation operation, ProactiveMemoryNoteWriteCapsule capsule) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capsule, "capsule");
    if (operation.state() != ProactiveMemoryNoteWriteOperation.State.CONSUMING
        || operation.anchor().state() != ProactiveMemoryNoteWriteAnchor.State.PENDING_APPROVAL
        || !capsule.matches(operation)) {
      return Optional.empty();
    }

    var scope =
        ProactiveMemoryNoteWriteScope.derive(
            operation.anchor().jobRef(), operation.anchor().targetHash());
    String requestId = requestIdFor(operation.reference());
    String content = MemoryManagementRules.content(capsule.source().safeText());
    String contentHash = MemoryManagementRules.contentHash(content);
    String argumentHash =
        MemoryManagementRules.writeArgumentHash(
            memoryType(), contentHash, emotionalWeight(), capsule.happenedAt());
    var replayQuery =
        new MemoryWriteReplayQuery(new MemoryMutationKey(scope, requestId), argumentHash);
    Optional<MemoryWriteResult> replay =
        Objects.requireNonNull(writer.replayUpsert(replayQuery), "replay");
    if (replay.isPresent()) {
      return Optional.of(receipt(replay.orElseThrow()));
    }

    EmbeddingResult embedding = embeddings.embed(new EmbeddingRequest(List.of(content)));
    if (embedding == null
        || embedding.vectors().size() != 1
        || !FIXED_EMBEDDING_MODEL.equals(embedding.model())) {
      throw new InvalidEmbeddingResponseException();
    }
    MemoryWriteResult result =
        Objects.requireNonNull(
            writer.upsert(
                new MemoryWriteCommand(
                    scope,
                    requestId,
                    ids.newMemoryItemId(),
                    memoryType(),
                    content,
                    contentHash,
                    embedding.vectors().getFirst(),
                    embedding.model(),
                    emotionalWeight(),
                    sourceKind(),
                    capsule.happenedAt(),
                    argumentHash,
                    clock.instant())),
            "write result");
    return Optional.of(receipt(result));
  }

  private static ProactiveMemoryNoteWriteSafeReceipt receipt(MemoryWriteResult result) {
    return new ProactiveMemoryNoteWriteSafeReceipt(result.status().name());
  }
}
