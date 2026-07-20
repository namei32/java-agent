package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveSchedule;
import io.namei.agent.kernel.proactive.ProactiveScheduleKind;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import io.namei.agent.kernel.proactive.ProactiveSourceKind;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ProactiveMemoryNoteWritePendingProducerTest {
  private static final Instant NOW = Instant.parse("2026-07-20T06:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void expiredCandidateCanBeSafelyRejectedMoreThanOnceWithoutCreatingPendingState() {
    Store store = new Store();
    ProactiveMemoryNoteWritePendingProducer producer = producer(store);
    LocalProactiveCandidateResult expired = candidate(true);

    assertThat(producer.prepare(expired, new TurnCancellationSource().token()).kind())
        .isEqualTo(ProactiveMemoryNoteWritePreparationOutcome.Kind.NOT_READY);
    assertThat(producer.prepare(expired, new TurnCancellationSource().token()).kind())
        .isEqualTo(ProactiveMemoryNoteWritePreparationOutcome.Kind.NOT_READY);

    assertThat(store.createAttempts).isZero();
  }

  @Test
  void cancellationAndNoCandidateCreateNoApprovalOrEncryptedCapsule() {
    Store store = new Store();
    ProactiveMemoryNoteWritePendingProducer producer = producer(store);
    TurnCancellationSource cancellation = new TurnCancellationSource();
    cancellation.cancel();

    assertThat(producer.prepare(candidate(false), cancellation.token()).kind())
        .isEqualTo(ProactiveMemoryNoteWritePreparationOutcome.Kind.CANCELLED);
    assertThat(
            producer
                .prepare(
                    LocalProactiveCandidateResult.skipped(ProactiveStableCode.PROACTIVE_NO_SOURCE),
                    new TurnCancellationSource().token())
                .kind())
        .isEqualTo(ProactiveMemoryNoteWritePreparationOutcome.Kind.NOT_READY);

    assertThat(store.createAttempts).isZero();
  }

  @Test
  void readyCandidateCreatesOnlyAP6BoundAndRedactedPendingOperation() {
    Store store = new Store();
    ProactiveMemoryNoteWritePendingProducer producer = producer(store);

    assertThat(producer.prepare(candidate(false), new TurnCancellationSource().token()).kind())
        .isEqualTo(ProactiveMemoryNoteWritePreparationOutcome.Kind.PENDING);

    assertThat(store.createAttempts).isEqualTo(1);
    assertThat(store.operation.approval().toolName())
        .isEqualTo(ProactiveMemoryNoteWriteCapability.CAPABILITY_NAME);
    assertThat(store.operation.approval().toolVersion())
        .isEqualTo(ProactiveMemoryNoteWriteCapability.CAPABILITY_VERSION);
    assertThat(store.operation.toString() + store.capsule)
        .doesNotContain("private source body", "daily-summary", "a".repeat(64));
  }

  private static ProactiveMemoryNoteWritePendingProducer producer(Store store) {
    return new ProactiveMemoryNoteWritePendingProducer(
        store,
        () -> ProactiveMemoryNoteWriteOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"),
        () -> ApprovalInboxReference.of("BBBBBBBBBBBBBBBBBBBBBB"),
        new FixedIds(),
        new AesGcmProactiveMemoryNoteWriteCapsuleCipher(
            new SecretKeySpec(new byte[32], "AES"),
            "fixture-key",
            new SecureRandom(new byte[] {8})),
        CLOCK,
        Duration.ofMinutes(5));
  }

  private static LocalProactiveCandidateResult candidate(boolean expired) {
    return LocalProactiveCandidateResult.candidateReady(
        new LocalProactiveCandidate(
            new ProactiveJobLease(
                new ScheduledJob(
                    ProactiveJobRef.parse("daily-summary"),
                    new ProactiveSchedule(ProactiveScheduleKind.AT, NOW, null),
                    "a".repeat(64),
                    "b".repeat(64),
                    ProactiveJobState.RUNNING,
                    1,
                    3),
                "proactive-local",
                expired ? NOW : NOW.plusSeconds(30),
                2),
            ProactiveSourceItem.fixedLocal(
                ProactiveSourceKind.FIXED_LOCAL, "fixture-memory", "private source body"),
            NOW));
  }

  private static final class FixedIds implements IdGenerator {
    @Override
    public String newTurnId() {
      return "turn-fixture";
    }

    @Override
    public String newApprovalId() {
      return "approval-fixture";
    }

    @Override
    public String newIdempotencyKey() {
      return "idempotency-fixture";
    }
  }

  private static final class Store implements ProactiveMemoryNoteWritePendingStore {
    private int createAttempts;
    private ProactiveMemoryNoteWriteOperation operation;
    private EncryptedProactiveMemoryNoteWriteCapsule capsule;

    @Override
    public ProactiveMemoryNoteWriteOperation create(
        ProactiveMemoryNoteWriteOperation value,
        ApprovalInboxEntry approval,
        EncryptedProactiveMemoryNoteWriteCapsule valueCapsule) {
      createAttempts++;
      operation = value;
      capsule = valueCapsule;
      return value;
    }

    @Override
    public Optional<ProactiveMemoryNoteWriteOperation> find(
        ProactiveMemoryNoteWriteOperationReference reference) {
      return Optional.empty();
    }
  }
}
