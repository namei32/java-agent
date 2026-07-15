package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.EmbeddingInvocationException;
import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryDeleteCommand;
import io.namei.agent.kernel.memory.MemoryDeleteResult;
import io.namei.agent.kernel.memory.MemoryDeleteStatus;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryMutation;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySearchRequest;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.memory.MemoryWriteReplayQuery;
import io.namei.agent.kernel.memory.MemoryWriteResult;
import io.namei.agent.kernel.memory.MemoryWriteStatus;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryStorePort;
import io.namei.agent.kernel.port.MemoryWritePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryManagementServiceTest {
  private static final Instant HAPPENED_AT = Instant.parse("2026-07-15T04:00:00Z");
  private static final Instant NOW = Instant.parse("2026-07-15T05:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final EmbeddingVector VECTOR = new EmbeddingVector(new float[] {1.0f, 0.0f});

  @Test
  void derivesScopeAndGoldenHashesThenEmbedsBeforeWriting() {
    var events = new ArrayList<String>();
    var embeddings = new RecordingEmbedding(events);
    var writer = new RecordingWriter(events);
    MemoryItemIdGenerator ids =
        () -> {
          events.add("id");
          return "memory-0001";
        };
    var service = new MemoryWriteService(embeddings, writer, ids, CLOCK);

    var outcome =
        service.write(
            "session-java-memory-001",
            new MemoryWriteRequest(
                "req-write-001", MemoryType.PREFERENCE, "  回答时\t先给结论  ", 2, HAPPENED_AT));

    assertThat(events).containsExactly("replay", "embed", "id", "upsert");
    assertThat(embeddings.request.texts()).containsExactly("回答时 先给结论");
    assertThat(writer.upsertCommand.scope().binding())
        .isEqualTo("971db4818fc2a938f4d66a981618ddd5e5c2e094d28e1a4eb669ac7c863cc02e");
    assertThat(writer.upsertCommand.content()).isEqualTo("回答时 先给结论");
    assertThat(writer.upsertCommand.contentHash())
        .isEqualTo("2045db36c8d05d336314d4fb52493f90be951eaa09b8a024e0339233d14909a7");
    assertThat(writer.upsertCommand.argumentHash())
        .isEqualTo("a22856775ceb7cac500204b3a15c1f2915b090de8d949bf8f401021b1e0538ba");
    assertThat(writer.upsertCommand.itemId()).isEqualTo("memory-0001");
    assertThat(writer.upsertCommand.embedding()).isEqualTo(VECTOR);
    assertThat(writer.upsertCommand.embeddingModel()).isEqualTo("embedding-model");
    assertThat(writer.upsertCommand.sourceKind()).isEqualTo(MemorySourceKind.EXPLICIT_API);
    assertThat(writer.upsertCommand.happenedAt()).isEqualTo(HAPPENED_AT);
    assertThat(writer.upsertCommand.requestedAt()).isEqualTo(NOW);
    assertThat(outcome.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(outcome.memory().content()).isEqualTo("回答时 先给结论");
  }

  @Test
  void replaysAnExistingWriteWithoutEmbeddingIdGenerationOrMutation() {
    var events = new ArrayList<String>();
    var embeddings = new RecordingEmbedding(events);
    var writer = new RecordingWriter(events);
    writer.replay = Optional.of(new MemoryWriteResult(MemoryWriteStatus.CREATED, item()));
    MemoryItemIdGenerator ids = () -> failIdGeneration();
    var service = new MemoryWriteService(embeddings, writer, ids, CLOCK);

    var result =
        service.write(
            "session-java-memory-001",
            new MemoryWriteRequest(
                "req-write-001", MemoryType.PREFERENCE, "回答时\t先给结论", 2, HAPPENED_AT));

    assertThat(events).containsExactly("replay");
    assertThat(embeddings.calls).isZero();
    assertThat(writer.upserts).isZero();
    assertThat(result.status()).isEqualTo(MemoryWriteStatus.CREATED);
    assertThat(result.memory().id()).isEqualTo("memory-0001");
  }

  @Test
  void embeddingFailurePerformsNoIdGenerationOrStoreMutation() {
    var events = new ArrayList<String>();
    var embeddings = new RecordingEmbedding(events);
    embeddings.failure =
        new EmbeddingInvocationException(new IllegalStateException("sensitive provider body"));
    var writer = new RecordingWriter(events);
    var service = new MemoryWriteService(embeddings, writer, () -> failIdGeneration(), CLOCK);

    assertThatThrownBy(
            () ->
                service.write(
                    "session-java-memory-001",
                    new MemoryWriteRequest("req-write-001", MemoryType.NOTE, "content", 0, null)))
        .isSameAs(embeddings.failure);
    assertThat(events).containsExactly("replay", "embed");
    assertThat(writer.upserts).isZero();
    assertThat(writer.deletes).isZero();
  }

  @Test
  void rejectsANullEmbeddingResponseBeforeIdGenerationOrStoreMutation() {
    var writer = new RecordingWriter(new ArrayList<>());
    var service = new MemoryWriteService(request -> null, writer, () -> failIdGeneration(), CLOCK);

    assertThatThrownBy(() -> service.write("session", request("req", "content")))
        .isInstanceOf(InvalidEmbeddingResponseException.class)
        .hasMessage("Embedding 响应无效");
    assertThat(writer.upserts).isZero();
    assertThat(writer.deletes).isZero();
  }

  @Test
  void collapsesWhitespaceWithoutCaseFoldingOrUnicodeNormalization() {
    var embeddings = new RecordingEmbedding(new ArrayList<>());
    var writer = new RecordingWriter(new ArrayList<>());
    var ids = new SequenceMemoryIds();
    var service = new MemoryWriteService(embeddings, writer, ids, CLOCK);

    service.write("session", request("req-1", "  Alpha\t Beta  "));
    service.write("session", request("req-2", "Alpha   Beta"));
    service.write("session", request("req-3", "alpha beta"));
    service.write("session", request("req-4", "é"));
    service.write("session", request("req-5", "e\u0301"));

    assertThat(writer.commands.get(0).content()).isEqualTo("Alpha Beta");
    assertThat(writer.commands.get(0).contentHash())
        .isEqualTo(writer.commands.get(1).contentHash());
    assertThat(writer.commands.get(0).contentHash())
        .isNotEqualTo(writer.commands.get(2).contentHash());
    assertThat(writer.commands.get(3).contentHash())
        .isNotEqualTo(writer.commands.get(4).contentHash());
  }

  @Test
  void listsOnlyPublicFieldsWithinTheDerivedScopeAndReturnsAnImmutableList() {
    var store = new RecordingStore(List.of(item()));
    var service = new MemoryQueryService(store);

    List<MemoryView> memories = service.list("session-java-memory-001");

    assertThat(store.scope.binding())
        .isEqualTo("971db4818fc2a938f4d66a981618ddd5e5c2e094d28e1a4eb669ac7c863cc02e");
    assertThat(store.limit).isEqualTo(100);
    assertThat(memories)
        .singleElement()
        .satisfies(
            memory -> {
              assertThat(memory.id()).isEqualTo("memory-0001");
              assertThat(memory.content()).isEqualTo("回答时 先给结论");
              assertThat(memory.happenedAt()).isEqualTo(HAPPENED_AT);
              assertThat(memory.toString()).doesNotContain(memory.id(), memory.content());
            });
    assertThat(
            Arrays.stream(MemoryView.class.getRecordComponents())
                .map(component -> component.getName()))
        .containsExactly(
            "id",
            "type",
            "content",
            "reinforcement",
            "emotionalWeight",
            "happenedAt",
            "createdAt",
            "updatedAt");
    assertThatThrownBy(() -> memories.clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void deletesWithScopeAndGoldenHashWhilePreservingNotFoundAndRetryIdentity() {
    var writer = new RecordingWriter(new ArrayList<>());
    writer.deleteStatus = MemoryDeleteStatus.NOT_FOUND;
    var service = new MemoryDeleteService(writer, CLOCK);

    var first = service.delete("session-java-memory-001", "req-delete-001", "memory-0001");
    var retry = service.delete("session-java-memory-001", "req-delete-001", "memory-0001");

    assertThat(first.status()).isEqualTo(MemoryDeleteStatus.NOT_FOUND);
    assertThat(retry).isEqualTo(first);
    assertThat(writer.deleteCommands)
        .hasSize(2)
        .allSatisfy(
            command -> {
              assertThat(command.scope().binding())
                  .isEqualTo("971db4818fc2a938f4d66a981618ddd5e5c2e094d28e1a4eb669ac7c863cc02e");
              assertThat(command.argumentHash())
                  .isEqualTo("f616218eb50197b7b366ceca8786373e89fca941bcd42349a078a5e0cb5e2439");
              assertThat(command.requestedAt()).isEqualTo(NOW);
            });

    service.delete("other-session", "req-delete-002", "memory-0001");
    assertThat(writer.deleteCommands.getLast().scope())
        .isNotEqualTo(writer.deleteCommands.getFirst().scope());
  }

  @Test
  void rejectsInvalidManagementInputsBeforeCallingAnyPort() {
    var embeddings = new RecordingEmbedding(new ArrayList<>());
    var writer = new RecordingWriter(new ArrayList<>());
    var writes = new MemoryWriteService(embeddings, writer, new SequenceMemoryIds(), CLOCK);
    var deletes = new MemoryDeleteService(writer, CLOCK);
    var queries = new MemoryQueryService(new RecordingStore(List.of()));

    assertThatThrownBy(() -> writes.write("bad/session", request("req", "content")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> writes.write(" session", request("req", "content")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new MemoryWriteRequest("bad request", MemoryType.NOTE, "content", 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryWriteRequest("req ", MemoryType.NOTE, "content", 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryWriteRequest("req", null, "content", 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryWriteRequest("req", MemoryType.NOTE, " \t ", 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryWriteRequest("req", MemoryType.NOTE, null, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new MemoryWriteRequest("req", MemoryType.NOTE, "x".repeat(4001), 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryWriteRequest("req", MemoryType.NOTE, "content", -1, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryWriteRequest("req", MemoryType.NOTE, "content", 11, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deletes.delete("session", "bad request", "memory-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deletes.delete("session", "req", "bad/item"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queries.list(" ")).isInstanceOf(IllegalArgumentException.class);

    assertThat(embeddings.calls).isZero();
    assertThat(writer.replays).isZero();
    assertThat(writer.upserts).isZero();
    assertThat(writer.deletes).isZero();
  }

  private static MemoryWriteRequest request(String requestId, String content) {
    return new MemoryWriteRequest(requestId, MemoryType.NOTE, content, 0, null);
  }

  private static String failIdGeneration() {
    throw new AssertionError("Memory ID must not be generated");
  }

  private static MemoryItem item() {
    return new MemoryItem(
        "memory-0001",
        new MemoryScope("971db4818fc2a938f4d66a981618ddd5e5c2e094d28e1a4eb669ac7c863cc02e"),
        MemoryType.PREFERENCE,
        "回答时 先给结论",
        "2".repeat(64),
        VECTOR,
        "embedding-model",
        1,
        2,
        MemorySourceKind.EXPLICIT_API,
        HAPPENED_AT,
        1,
        NOW,
        NOW);
  }

  private static final class RecordingEmbedding implements EmbeddingPort {
    private final List<String> events;
    private int calls;
    private EmbeddingRequest request;
    private RuntimeException failure;

    private RecordingEmbedding(List<String> events) {
      this.events = events;
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
      events.add("embed");
      calls++;
      this.request = request;
      if (failure != null) {
        throw failure;
      }
      return new EmbeddingResult("embedding-model", 2, List.of(VECTOR));
    }
  }

  private static final class RecordingWriter implements MemoryWritePort {
    private final List<String> events;
    private final List<MemoryWriteCommand> commands = new ArrayList<>();
    private final List<MemoryDeleteCommand> deleteCommands = new ArrayList<>();
    private Optional<MemoryWriteResult> replay = Optional.empty();
    private MemoryDeleteStatus deleteStatus = MemoryDeleteStatus.DELETED;
    private int replays;
    private int upserts;
    private int deletes;
    private MemoryWriteCommand upsertCommand;

    private RecordingWriter(List<String> events) {
      this.events = events;
    }

    @Override
    public Optional<MemoryMutation> findMutation(MemoryMutationKey key) {
      return Optional.empty();
    }

    @Override
    public Optional<MemoryWriteResult> replayUpsert(MemoryWriteReplayQuery query) {
      events.add("replay");
      replays++;
      return replay;
    }

    @Override
    public MemoryWriteResult upsert(MemoryWriteCommand command) {
      events.add("upsert");
      upserts++;
      upsertCommand = command;
      commands.add(command);
      return new MemoryWriteResult(
          MemoryWriteStatus.CREATED,
          new MemoryItem(
              command.itemId(),
              command.scope(),
              command.type(),
              command.content(),
              command.contentHash(),
              command.embedding(),
              command.embeddingModel(),
              1,
              command.emotionalWeight(),
              command.sourceKind(),
              command.happenedAt(),
              1,
              command.requestedAt(),
              command.requestedAt()));
    }

    @Override
    public MemoryDeleteResult delete(MemoryDeleteCommand command) {
      deletes++;
      deleteCommands.add(command);
      return new MemoryDeleteResult(deleteStatus, command.itemId());
    }
  }

  private static final class RecordingStore implements MemoryStorePort {
    private final List<MemoryItem> items;
    private MemoryScope scope;
    private int limit;

    private RecordingStore(List<MemoryItem> items) {
      this.items = items;
    }

    @Override
    public long candidateCount(MemoryScope scope) {
      return items.size();
    }

    @Override
    public List<MemoryItem> loadCandidates(MemorySearchRequest request) {
      return items;
    }

    @Override
    public List<MemoryItem> list(MemoryScope scope, int limit) {
      this.scope = scope;
      this.limit = limit;
      return items;
    }
  }

  private static final class SequenceMemoryIds implements MemoryItemIdGenerator {
    private int sequence;

    @Override
    public String newMemoryItemId() {
      sequence++;
      return "memory-" + sequence;
    }
  }
}
