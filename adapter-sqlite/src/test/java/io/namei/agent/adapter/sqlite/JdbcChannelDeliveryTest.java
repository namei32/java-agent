package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.reliability.ChannelFingerprint;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.DeliveryAttemptOutcome;
import io.namei.agent.kernel.channel.reliability.DeliveryEnvelope;
import io.namei.agent.kernel.channel.reliability.DeliveryMessageType;
import io.namei.agent.kernel.channel.reliability.DeliveryPartState;
import io.namei.agent.kernel.channel.reliability.DeliverySourceKind;
import io.namei.agent.kernel.channel.reliability.DeliveryState;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcChannelDeliveryTest {
  private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");
  private static final ChannelInstanceId INSTANCE = ChannelInstanceId.derive("telegram", "100001");
  private static final String REQUEST = "a".repeat(64);
  private static final String OWNER_A = "1".repeat(32);
  private static final String OWNER_B = "2".repeat(32);

  @TempDir Path tempDir;

  private ChannelLedgerSchemaInitializer schema;
  private JdbcChannelLedger ledger;

  @BeforeEach
  void initialize() {
    schema =
        new ChannelLedgerSchemaInitializer(
            tempDir.resolve("workspace/channels/channel-ledger.db"), 5_000);
    schema.initialize();
    ledger = new JdbcChannelLedger(schema);
  }

  @Test
  void terminalOutboxIsAtomicIdempotentAndBoundToClaim() throws Exception {
    runningTurn("update-0", 0, "message-1", "turn-1");
    DeliveryEnvelope delivery = terminal("delivery-1", "turn-1", "part-one", "part-two");

    ChannelLedgerResult.Terminal created =
        ledger.recordTerminal(
            new ChannelLedgerCommand.RecordTerminal(INSTANCE, "turn-1", 1, delivery, NOW));
    ChannelLedgerResult.Terminal replayed =
        ledger.recordTerminal(
            new ChannelLedgerCommand.RecordTerminal(INSTANCE, "turn-1", 1, delivery, NOW));

    assertThat(created)
        .isEqualTo(
            new ChannelLedgerResult.Terminal(
                ChannelLedgerResult.TerminalStatus.CREATED, "delivery-1", 2));
    assertThat(replayed)
        .isEqualTo(
            new ChannelLedgerResult.Terminal(
                ChannelLedgerResult.TerminalStatus.REPLAYED, "delivery-1", 2));
    assertThat(delivery("delivery-1")).isEqualTo("PENDING:2:0:0:null:null:null");
    assertThat(parts("delivery-1"))
        .containsExactly(
            "0:part-one:PENDING:0:null:null:null", "1:part-two:PENDING:0:null:null:null");
    assertThat(claimState("turn-1")).isEqualTo("TERMINAL_RECORDED:2:null:null");

    DeliveryEnvelope conflict = terminal("delivery-other", "turn-1", "changed");
    assertConflict(
        () ->
            ledger.recordTerminal(
                new ChannelLedgerCommand.RecordTerminal(INSTANCE, "turn-1", 2, conflict, NOW)));
    assertThat(count("channel_deliveries")).isOne();
  }

  @Test
  void terminalCommitFaultLeavesRunningClaimAndNoOutbox() throws Exception {
    runningTurn("update-0", 0, "message-1", "turn-1");
    var faultHits = new AtomicInteger();
    var faulting =
        new JdbcChannelLedger(
            schema,
            point -> {
              if (point == ChannelLedgerFaultPoint.TERMINAL_RECORDED_BEFORE_COMMIT) {
                faultHits.incrementAndGet();
                throw new SQLException("sensitive-terminal-commit-failure");
              }
            });

    assertThatThrownBy(
            () ->
                faulting.recordTerminal(
                    new ChannelLedgerCommand.RecordTerminal(
                        INSTANCE, "turn-1", 1, terminal("delivery-1", "turn-1", "payload"), NOW)))
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessage("渠道账本操作失败")
        .hasMessageNotContaining("sensitive-terminal-commit-failure");

    assertThat(faultHits).hasValue(1);
    assertThat(claimState("turn-1")).isEqualTo("RUNNING:1:" + OWNER_A + ":" + NOW.plusSeconds(60));
    assertThat(count("channel_deliveries")).isZero();
    assertThat(count("channel_delivery_parts")).isZero();
  }

  @Test
  void feedbackEventCursorAndOutboxCommitTogether() throws Exception {
    ChannelLedgerCommand.RecordEvent feedback =
        feedback("update-0", 0, "delivery-feedback", "busy");

    ChannelLedgerResult.Event created = ledger.recordEvent(feedback);
    ChannelLedgerResult.Event replayed = ledger.recordEvent(feedback);

    assertThat(created)
        .isEqualTo(
            new ChannelLedgerResult.Event(
                ChannelLedgerResult.InboxStatus.FEEDBACK_QUEUED, null, 0, "delivery-feedback", 1));
    assertThat(replayed).isEqualTo(created);
    assertThat(delivery("delivery-feedback")).isEqualTo("PENDING:1:0:0:null:null:null");
    assertThat(parts("delivery-feedback")).containsExactly("0:busy:PENDING:0:null:null:null");
    assertThat(count("channel_inbox_events")).isOne();
    assertThat(cursor()).isEqualTo("1:0");

    assertConflict(
        () -> ledger.recordEvent(feedback("update-0", 0, "delivery-feedback", "changed")));
    assertThat(parts("delivery-feedback")).containsExactly("0:busy:PENDING:0:null:null:null");
  }

  @Test
  void claimPersistsInFlightAttemptBeforeReturningWorkAndHasOneWinner() throws Exception {
    ledger.recordEvent(feedback("update-0", 0, "delivery-1", "payload"));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var results = new ArrayList<Optional<ChannelLedgerResult.DeliveryWork>>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> claimAfterBarrier(OWNER_A, ready, start));
      var second = executor.submit(() -> claimAfterBarrier(OWNER_B, ready, start));
      ready.await();
      start.countDown();
      results.add(first.get());
      results.add(second.get());
    }

    assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
    ChannelLedgerResult.DeliveryWork work =
        results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
    assertThat(work.deliveryId()).isEqualTo("delivery-1");
    assertThat(work.payload()).isEqualTo("payload");
    assertThat(work.attemptNumber()).isOne();
    assertThat(delivery("delivery-1"))
        .isEqualTo("DELIVERING:1:0:1:" + work.ownerId() + ":" + NOW.plusSeconds(60) + ":null");
    assertThat(parts("delivery-1")).containsExactly("0:payload:IN_FLIGHT:1:null:null:null");
    assertThat(attempts("delivery-1")).containsExactly("0:1:STARTED:null:null:null");
  }

  @Test
  void confirmedPartsAdvanceStrictlyAndAreNeverReclaimed() throws Exception {
    runningTurn("update-0", 0, "message-1", "turn-1");
    ledger.recordTerminal(
        new ChannelLedgerCommand.RecordTerminal(
            INSTANCE, "turn-1", 1, terminal("delivery-1", "turn-1", "one", "two"), NOW));

    ChannelLedgerResult.DeliveryWork first = claim(OWNER_A, NOW).orElseThrow();
    ChannelLedgerResult.DeliveryUpdate firstUpdate =
        ledger.recordDeliveryOutcome(success(first, "501", NOW.plusSeconds(1)));
    ChannelLedgerResult.DeliveryWork second = claim(OWNER_A, NOW.plusSeconds(2)).orElseThrow();
    ChannelLedgerResult.DeliveryUpdate secondUpdate =
        ledger.recordDeliveryOutcome(success(second, "502", NOW.plusSeconds(3)));
    ChannelLedgerResult.DeliveryUpdate replay =
        ledger.recordDeliveryOutcome(success(second, "502", NOW.plusSeconds(3)));

    assertThat(firstUpdate)
        .isEqualTo(
            new ChannelLedgerResult.DeliveryUpdate(
                DeliveryState.PENDING, DeliveryPartState.DELIVERED, 1, 2));
    assertThat(second.partIndex()).isOne();
    assertThat(secondUpdate)
        .isEqualTo(
            new ChannelLedgerResult.DeliveryUpdate(
                DeliveryState.DELIVERED, DeliveryPartState.DELIVERED, 2, 4));
    assertThat(replay).isEqualTo(secondUpdate);
    assertThat(claim(OWNER_A, NOW.plusSeconds(4))).isEmpty();
    assertThat(delivery("delivery-1")).isEqualTo("DELIVERED:2:2:4:null:null:null");
    assertThat(parts("delivery-1"))
        .containsExactly("0:one:DELIVERED:1:null:501:null", "1:two:DELIVERED:1:null:502:null");
  }

  @Test
  void rateLimitRetriesOnceAtPersistedDueTimeThenExhaustsBudget() throws Exception {
    ledger.recordEvent(feedback("update-0", 0, "delivery-1", "payload"));
    ChannelLedgerResult.DeliveryWork first = claim(OWNER_A, NOW).orElseThrow();
    Instant due = NOW.plusSeconds(30);

    ChannelLedgerResult.DeliveryUpdate waiting =
        ledger.recordDeliveryOutcome(
            outcome(
                first,
                DeliveryAttemptOutcome.RETRYABLE_REJECTED,
                null,
                due,
                "RATE_LIMITED",
                NOW.plusSeconds(1)));

    assertThat(waiting)
        .isEqualTo(
            new ChannelLedgerResult.DeliveryUpdate(
                DeliveryState.PENDING, DeliveryPartState.RETRY_WAIT, 0, 2));
    assertThat(claim(OWNER_A, due.minusNanos(1))).isEmpty();
    ChannelLedgerResult.DeliveryWork second = claim(OWNER_A, due).orElseThrow();
    assertThat(second.attemptNumber()).isEqualTo(2);
    ChannelLedgerResult.DeliveryUpdate exhausted =
        ledger.recordDeliveryOutcome(
            outcome(
                second,
                DeliveryAttemptOutcome.RETRYABLE_REJECTED,
                null,
                due.plusSeconds(30),
                "RATE_LIMITED",
                due.plusSeconds(1)));

    assertThat(exhausted)
        .isEqualTo(
            new ChannelLedgerResult.DeliveryUpdate(
                DeliveryState.FAILED, DeliveryPartState.FAILED, 0, 4));
    assertThat(claim(OWNER_A, due.plusSeconds(40))).isEmpty();
    assertThat(delivery("delivery-1")).isEqualTo("FAILED:1:0:4:null:null:RETRY_BUDGET_EXCEEDED");
    assertThat(parts("delivery-1"))
        .containsExactly("0:payload:FAILED:2:null:null:RETRY_BUDGET_EXCEEDED");
  }

  @Test
  void permanentAndUnknownOutcomesStopOnlyTheirDelivery() throws Exception {
    ledger.recordEvent(feedback("update-0", 0, "delivery-a", "first"));
    ledger.recordEvent(feedback("update-1", 1, "delivery-b", "second"));

    ChannelLedgerResult.DeliveryWork permanent = claim(OWNER_A, NOW).orElseThrow();
    assertThat(permanent.deliveryId()).isEqualTo("delivery-a");
    ledger.recordDeliveryOutcome(
        outcome(
            permanent,
            DeliveryAttemptOutcome.PERMANENT_REJECTED,
            null,
            null,
            "CHAT_NOT_FOUND",
            NOW.plusSeconds(1)));
    ChannelLedgerResult.DeliveryWork unknown = claim(OWNER_A, NOW.plusSeconds(2)).orElseThrow();
    assertThat(unknown.deliveryId()).isEqualTo("delivery-b");
    ledger.recordDeliveryOutcome(
        outcome(
            unknown,
            DeliveryAttemptOutcome.UNKNOWN,
            null,
            null,
            "SEND_TIMEOUT",
            NOW.plusSeconds(3)));

    assertThat(delivery("delivery-a")).contains("FAILED:").endsWith(":CHAT_NOT_FOUND");
    assertThat(delivery("delivery-b")).contains("UNKNOWN:").endsWith(":SEND_TIMEOUT");
    assertThat(ledger.snapshot(INSTANCE).unknownDeliveries()).isOne();
  }

  @Test
  void ownerMismatchAndOutcomeCommitFaultCannotForgeReceipt() throws Exception {
    ledger.recordEvent(feedback("update-0", 0, "delivery-1", "payload"));
    ChannelLedgerResult.DeliveryWork work = claim(OWNER_A, NOW).orElseThrow();
    ChannelLedgerCommand.RecordDeliveryOutcome wrongOwner =
        new ChannelLedgerCommand.RecordDeliveryOutcome(
            work.deliveryId(),
            work.partIndex(),
            work.attemptNumber(),
            OWNER_B,
            DeliveryAttemptOutcome.SUCCEEDED,
            "501",
            null,
            "",
            NOW.plusSeconds(1));
    assertThatThrownBy(() -> ledger.recordDeliveryOutcome(wrongOwner))
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.STALE_WRITE);

    var faultHits = new AtomicInteger();
    var faulting =
        new JdbcChannelLedger(
            schema,
            point -> {
              if (point == ChannelLedgerFaultPoint.DELIVERY_OUTCOME_BEFORE_COMMIT) {
                faultHits.incrementAndGet();
                throw new SQLException("sensitive-receipt-commit-failure");
              }
            });
    assertThatThrownBy(
            () -> faulting.recordDeliveryOutcome(success(work, "501", NOW.plusSeconds(1))))
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessageNotContaining("sensitive-receipt-commit-failure");

    assertThat(faultHits).hasValue(1);
    assertThat(delivery("delivery-1"))
        .isEqualTo("DELIVERING:1:0:1:" + OWNER_A + ":" + NOW.plusSeconds(60) + ":null");
    assertThat(parts("delivery-1")).containsExactly("0:payload:IN_FLIGHT:1:null:null:null");
    assertThat(attempts("delivery-1")).containsExactly("0:1:STARTED:null:null:null");
  }

  private void runningTurn(String eventId, long sequence, String externalMessageId, String turnId) {
    String fingerprint =
        ChannelFingerprint.event(INSTANCE, eventId, sequence, InboxEventKind.TURN, "", REQUEST);
    ledger.recordEvent(
        new ChannelLedgerCommand.RecordEvent(
            INSTANCE,
            eventId,
            sequence,
            fingerprint,
            InboxEventKind.TURN,
            "",
            REQUEST,
            new ChannelLedgerCommand.TurnReservation(externalMessageId, REQUEST, turnId),
            null,
            NOW));
    ledger.startTurn(
        new ChannelLedgerCommand.StartTurn(
            INSTANCE, eventId, externalMessageId, turnId, OWNER_A, 0, NOW.plusSeconds(60), NOW));
  }

  private ChannelLedgerCommand.RecordEvent feedback(
      String eventId, long sequence, String deliveryId, String payload) {
    String fingerprint =
        ChannelFingerprint.event(
            INSTANCE, eventId, sequence, InboxEventKind.FEEDBACK, "SESSION_BUSY", "");
    DeliveryEnvelope delivery =
        DeliveryEnvelope.create(
            INSTANCE,
            deliveryId,
            "10001",
            DeliverySourceKind.CHANNEL_FEEDBACK,
            eventId,
            DeliveryMessageType.SESSION_BUSY,
            "",
            false,
            "telegram-text-chunks-v1",
            java.util.List.of(payload));
    return new ChannelLedgerCommand.RecordEvent(
        INSTANCE,
        eventId,
        sequence,
        fingerprint,
        InboxEventKind.FEEDBACK,
        "SESSION_BUSY",
        "",
        null,
        delivery,
        NOW);
  }

  private DeliveryEnvelope terminal(String deliveryId, String turnId, String... parts) {
    return DeliveryEnvelope.create(
        INSTANCE,
        deliveryId,
        "10001",
        DeliverySourceKind.TURN_TERMINAL,
        turnId,
        DeliveryMessageType.TURN_COMPLETED,
        "",
        false,
        "telegram-text-chunks-v1",
        java.util.List.of(parts));
  }

  private Optional<ChannelLedgerResult.DeliveryWork> claim(String owner, Instant at) {
    return ledger.claimNextDelivery(
        new ChannelLedgerCommand.ClaimDelivery(INSTANCE, owner, at.plusSeconds(60), at));
  }

  private Optional<ChannelLedgerResult.DeliveryWork> claimAfterBarrier(
      String owner, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      start.await();
      return claim(owner, NOW);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private ChannelLedgerCommand.RecordDeliveryOutcome success(
      ChannelLedgerResult.DeliveryWork work, String remoteId, Instant completedAt) {
    return outcome(work, DeliveryAttemptOutcome.SUCCEEDED, remoteId, null, "", completedAt);
  }

  private ChannelLedgerCommand.RecordDeliveryOutcome outcome(
      ChannelLedgerResult.DeliveryWork work,
      DeliveryAttemptOutcome outcome,
      String remoteId,
      Instant retryAt,
      String code,
      Instant completedAt) {
    return new ChannelLedgerCommand.RecordDeliveryOutcome(
        work.deliveryId(),
        work.partIndex(),
        work.attemptNumber(),
        work.ownerId(),
        outcome,
        remoteId,
        retryAt,
        code,
        completedAt);
  }

  private void assertConflict(ThrowingWork work) {
    assertThatThrownBy(work::run)
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.IDEMPOTENCY_CONFLICT);
  }

  private long count(String table) throws Exception {
    try (var connection = schema.openConnection();
        var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private String cursor() throws Exception {
    try (var connection = schema.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery("SELECT next_sequence, revision FROM channel_cursors")) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1) + ":" + rows.getLong(2);
    }
  }

  private String claimState(String turnId) throws Exception {
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                "SELECT state, revision, owner_id, lease_expires_at"
                    + " FROM channel_turn_claims WHERE turn_id = ?")) {
      statement.setString(1, turnId);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1)
            + ":"
            + rows.getLong(2)
            + ":"
            + rows.getString(3)
            + ":"
            + rows.getString(4);
      }
    }
  }

  private String delivery(String deliveryId) throws Exception {
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT state, part_count, next_part_index, revision,
                  owner_id, lease_expires_at, last_error_code
                FROM channel_deliveries WHERE delivery_id = ?
                """)) {
      statement.setString(1, deliveryId);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1)
            + ":"
            + rows.getInt(2)
            + ":"
            + rows.getInt(3)
            + ":"
            + rows.getLong(4)
            + ":"
            + rows.getString(5)
            + ":"
            + rows.getString(6)
            + ":"
            + rows.getString(7);
      }
    }
  }

  private java.util.List<String> parts(String deliveryId) throws Exception {
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT part_index, payload_text, state, attempt_count,
                  next_attempt_at, remote_message_id, last_error_code
                FROM channel_delivery_parts WHERE delivery_id = ? ORDER BY part_index
                """)) {
      statement.setString(1, deliveryId);
      try (var rows = statement.executeQuery()) {
        var values = new ArrayList<String>();
        while (rows.next()) {
          values.add(
              rows.getInt(1)
                  + ":"
                  + rows.getString(2)
                  + ":"
                  + rows.getString(3)
                  + ":"
                  + rows.getInt(4)
                  + ":"
                  + rows.getString(5)
                  + ":"
                  + rows.getString(6)
                  + ":"
                  + rows.getString(7));
        }
        return values;
      }
    }
  }

  private java.util.List<String> attempts(String deliveryId) throws Exception {
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT part_index, attempt_number, outcome,
                  completed_at, remote_message_id, error_code
                FROM channel_delivery_attempts WHERE delivery_id = ?
                ORDER BY part_index, attempt_number
                """)) {
      statement.setString(1, deliveryId);
      try (var rows = statement.executeQuery()) {
        var values = new ArrayList<String>();
        while (rows.next()) {
          values.add(
              rows.getInt(1)
                  + ":"
                  + rows.getInt(2)
                  + ":"
                  + rows.getString(3)
                  + ":"
                  + rows.getString(4)
                  + ":"
                  + rows.getString(5)
                  + ":"
                  + rows.getString(6));
        }
        return values;
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingWork {
    void run() throws Exception;
  }
}
