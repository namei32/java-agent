package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;
import io.namei.agent.kernel.memory.EmbeddingVector;
import io.namei.agent.kernel.memory.MemoryItem;
import io.namei.agent.kernel.memory.MemoryMutation;
import io.namei.agent.kernel.memory.MemoryMutationKey;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.memory.MemoryWriteCommand;
import io.namei.agent.kernel.memory.MemoryWriteReplayQuery;
import io.namei.agent.kernel.memory.MemoryWriteResult;
import io.namei.agent.kernel.memory.MemoryWriteStatus;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryWritePort;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import io.namei.agent.kernel.proactive.ProactiveSourceKind;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProactiveMemoryNoteWriteCapabilityTest {
  private static final Instant NOW = Instant.parse("2026-07-20T06:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Instant HAPPENED_AT = NOW.minusSeconds(60);

  @Test
  void writesExactlyOneApprovedNoteWithDedicatedScopeAndProvenance() {
    Scenario scenario = Scenario.consuming();

    assertThat(scenario.capability.write(scenario.operation, scenario.capsule).code())
        .isEqualTo("CREATED");

    assertThat(scenario.embeddings.calls).isEqualTo(1);
    assertThat(scenario.writer.upsertCalls).isEqualTo(1);
    assertThat(scenario.writer.command.type()).isEqualTo(MemoryType.NOTE);
    assertThat(scenario.writer.command.emotionalWeight()).isZero();
    assertThat(scenario.writer.command.sourceKind()).isEqualTo(MemorySourceKind.PROACTIVE_APPROVED);
    assertThat(scenario.writer.command.requestId()).isEqualTo("p6-note-AAAAAAAAAAAAAAAAAAAAAA");
    assertThat(scenario.writer.command.scope().binding())
        .isEqualTo(
            ProactiveMemoryNoteWriteScope.derive(
                    ProactiveJobRef.parse("daily-summary"), "a".repeat(64))
                .binding());
    assertThat(scenario.writer.command.content()).isEqualTo("private source body");
  }

  @Test
  void returnsLedgerReplayWithoutEmbeddingOrAnotherWrite() {
    Scenario scenario = Scenario.consuming();
    scenario.writer.replay =
        Optional.of(new MemoryWriteResult(MemoryWriteStatus.REINFORCED, item()));

    assertThat(scenario.capability.write(scenario.operation, scenario.capsule).code())
        .isEqualTo("REINFORCED");

    assertThat(scenario.embeddings.calls).isZero();
    assertThat(scenario.writer.upsertCalls).isZero();
    assertThat(scenario.writer.replayQuery).isNotNull();
  }

  @Test
  void rejectsAnyStateOtherThanReservedConsumptionBeforeEmbeddingOrWrite() {
    Scenario scenario = Scenario.consuming();
    var unapproved =
        ProactiveMemoryNoteWriteOperation.pending(
            scenario.operation.reference(),
            scenario.operation.approval(),
            scenario.operation.anchor(),
            NOW);

    assertThat(scenario.capability.tryWrite(unapproved, scenario.capsule)).isEmpty();

    assertThat(scenario.embeddings.calls).isZero();
    assertThat(scenario.writer.upsertCalls).isZero();
    assertThat(scenario.writer.replayQuery).isNull();
  }

  @Test
  void rejectsAnUnexpectedEmbeddingModelBeforeAnySqliteWrite() {
    Scenario scenario = Scenario.consuming();
    scenario.embeddings.model = "other-model";

    assertThatThrownBy(() -> scenario.capability.write(scenario.operation, scenario.capsule))
        .isInstanceOf(io.namei.agent.kernel.error.InvalidEmbeddingResponseException.class);

    assertThat(scenario.embeddings.calls).isEqualTo(1);
    assertThat(scenario.writer.upsertCalls).isZero();
  }

  private static MemoryItem item() {
    return new MemoryItem(
        "memory-0001",
        ProactiveMemoryNoteWriteScope.derive(
            ProactiveJobRef.parse("daily-summary"), "a".repeat(64)),
        MemoryType.NOTE,
        "private source body",
        "e".repeat(64),
        new EmbeddingVector(new float[] {1.0f, 0.0f}),
        "fake-r14-p6-memory-v1",
        1,
        0,
        MemorySourceKind.PROACTIVE_APPROVED,
        NOW,
        1,
        NOW,
        NOW);
  }

  private static final class Scenario {
    private final RecordingEmbeddings embeddings = new RecordingEmbeddings();
    private final RecordingWriter writer = new RecordingWriter();
    private final ProactiveMemoryNoteWriteCapability capability =
        new ProactiveMemoryNoteWriteCapability(embeddings, writer, () -> "memory-0001", CLOCK);
    private final ProactiveMemoryNoteWriteOperation operation = consumingOperation();
    private final ProactiveMemoryNoteWriteCapsule capsule =
        ProactiveMemoryNoteWriteCapsule.forOperation(operation, source(), HAPPENED_AT);

    static Scenario consuming() {
      return new Scenario();
    }

    private static ProactiveMemoryNoteWriteOperation consumingOperation() {
      var reference = ProactiveMemoryNoteWriteOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA");
      var approval =
          new ApprovalRequest(
              "approval-fixture",
              "b".repeat(64),
              "turn-fixture",
              "call-fixture",
              ProactiveMemoryNoteWriteCapability.CAPABILITY_NAME,
              ProactiveMemoryNoteWriteCapability.CAPABILITY_VERSION,
              ToolRisk.WRITE,
              ApprovalFingerprint.argumentsHash(
                  ProactiveMemoryNoteWriteCapsule.argumentsFor(
                      source(), "daily-summary", "a".repeat(64), HAPPENED_AT)),
              "idempotency-fixture",
              "请求保存本地主动记忆候选。",
              NOW,
              NOW.plusSeconds(300),
              ApprovalRequest.FINGERPRINT_VERSION,
              "d".repeat(64));
      var pending =
          ProactiveMemoryNoteWriteOperation.pending(
              reference,
              approval,
              ProactiveMemoryNoteWriteAnchor.pending(
                  reference, ProactiveJobRef.parse("daily-summary"), "a".repeat(64)),
              NOW);
      return pending
          .transition(
              ProactiveMemoryNoteWriteOperation.State.APPROVED_PENDING_RESUME, NOW.plusSeconds(1))
          .transition(ProactiveMemoryNoteWriteOperation.State.CONSUMING, NOW.plusSeconds(2));
    }
  }

  private static final class RecordingEmbeddings implements EmbeddingPort {
    private int calls;
    private String model = "fake-r14-p6-memory-v1";

    @Override
    public EmbeddingResult embed(io.namei.agent.kernel.memory.EmbeddingRequest request) {
      calls++;
      return new EmbeddingResult(model, 2, List.of(new EmbeddingVector(new float[] {1.0f, 0.0f})));
    }
  }

  private static final class RecordingWriter implements MemoryWritePort {
    private int upsertCalls;
    private MemoryWriteCommand command;
    private MemoryWriteReplayQuery replayQuery;
    private Optional<MemoryWriteResult> replay = Optional.empty();

    @Override
    public Optional<MemoryMutation> findMutation(MemoryMutationKey key) {
      return Optional.empty();
    }

    @Override
    public Optional<MemoryWriteResult> replayUpsert(MemoryWriteReplayQuery value) {
      replayQuery = value;
      return replay;
    }

    @Override
    public MemoryWriteResult upsert(MemoryWriteCommand value) {
      upsertCalls++;
      command = value;
      return new MemoryWriteResult(MemoryWriteStatus.CREATED, item());
    }

    @Override
    public io.namei.agent.kernel.memory.MemoryDeleteResult delete(
        io.namei.agent.kernel.memory.MemoryDeleteCommand command) {
      throw new UnsupportedOperationException();
    }
  }

  private static ProactiveSourceItem source() {
    return ProactiveSourceItem.fixedLocal(
        ProactiveSourceKind.FIXED_LOCAL, "fixture-memory", "private source body");
  }
}
