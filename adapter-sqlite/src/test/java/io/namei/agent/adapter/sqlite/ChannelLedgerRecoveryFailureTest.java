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
import io.namei.agent.kernel.channel.reliability.DeliverySourceKind;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("failure")
class ChannelLedgerRecoveryFailureTest {
  private static final Instant NOW = Instant.parse("2026-07-16T05:00:00Z");
  private static final Instant OLD = Instant.parse("2026-05-01T00:00:00Z");
  private static final Instant CUTOFF = Instant.parse("2026-06-01T00:00:00Z");
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
  void claimRecoveryIsBoundedStableAndIdempotent() throws Exception {
    runningTurn("update-0", 0, "message-0", "turn-0");
    runningTurn("update-1", 1, "message-1", "turn-1");
    reserveTurn("update-2", 2, "message-2", "turn-2");

    ChannelLedgerResult.Recovery first = recover(2);
    ChannelLedgerResult.Recovery second = recover(2);
    ChannelLedgerResult.Recovery third = recover(2);

    assertThat(first).isEqualTo(new ChannelLedgerResult.Recovery(2, true));
    assertThat(second).isEqualTo(new ChannelLedgerResult.Recovery(1, false));
    assertThat(third).isEqualTo(new ChannelLedgerResult.Recovery(0, false));
    assertThat(claimStates())
        .containsExactly(
            "turn-0:EXECUTION_UNKNOWN:1", "turn-1:EXECUTION_UNKNOWN:1", "turn-2:START_RETRYABLE:1");
    assertThat(ledger.snapshot(INSTANCE).unknownExecutions()).isEqualTo(2);
  }

  @Test
  void deliveryRecoveryPreservesPendingAndRetryButMakesInFlightUnknown() throws Exception {
    ledger.recordEvent(feedback("update-0", 0, "delivery-a-retry", "retry"));
    ChannelLedgerResult.DeliveryWork retry = claim(NOW).orElseThrow();
    ledger.recordDeliveryOutcome(
        outcome(
            retry,
            DeliveryAttemptOutcome.RETRYABLE_REJECTED,
            null,
            NOW.plusSeconds(60),
            "RATE_LIMITED",
            NOW.plusSeconds(1)));
    ledger.recordEvent(feedback("update-1", 1, "delivery-b-inflight", "inflight"));
    ledger.recordEvent(feedback("update-2", 2, "delivery-c-pending", "pending"));
    ChannelLedgerResult.DeliveryWork inFlight = claim(NOW.plusSeconds(2)).orElseThrow();
    assertThat(inFlight.deliveryId()).isEqualTo("delivery-b-inflight");

    ChannelLedgerResult.Recovery recovered = recover(10);

    assertThat(recovered).isEqualTo(new ChannelLedgerResult.Recovery(1, false));
    assertThat(deliveryState("delivery-a-retry")).isEqualTo("PENDING:null:null");
    assertThat(partState("delivery-a-retry")).isEqualTo("RETRY_WAIT:RATE_LIMITED");
    assertThat(deliveryState("delivery-c-pending")).isEqualTo("PENDING:null:null");
    assertThat(partState("delivery-c-pending")).isEqualTo("PENDING:null");
    assertThat(deliveryState("delivery-b-inflight"))
        .isEqualTo("UNKNOWN:null:RECOVERY_OUTCOME_UNKNOWN");
    assertThat(partState("delivery-b-inflight")).isEqualTo("UNKNOWN:RECOVERY_OUTCOME_UNKNOWN");
    assertThat(attemptState("delivery-b-inflight"))
        .isEqualTo("UNKNOWN:" + NOW.plusSeconds(10) + ":RECOVERY_OUTCOME_UNKNOWN");
    assertThat(ledger.snapshot(INSTANCE).unknownDeliveries()).isOne();
  }

  @Test
  void cleanupPrunesOnlyResolvedPayloadAndEventsBelowCursor() throws Exception {
    createResolved("update-0", 0, "delivery-a", DeliveryAttemptOutcome.SUCCEEDED);
    createResolved("update-1", 1, "delivery-b", DeliveryAttemptOutcome.PERMANENT_REJECTED);
    createResolved("update-2", 2, "delivery-c", DeliveryAttemptOutcome.UNKNOWN);
    ageRows();
    try (var connection = schema.openConnection()) {
      connection
          .createStatement()
          .executeUpdate("UPDATE channel_cursors SET next_sequence = 2, revision = revision + 1");
    }

    ChannelLedgerResult.Cleanup cleanup = cleanup(10, 100, 100);

    assertThat(cleanup).isEqualTo(new ChannelLedgerResult.Cleanup(4, true));
    assertThat(payloadPruned("delivery-a")).isOne();
    assertThat(payloadPruned("delivery-b")).isOne();
    assertThat(payloadPruned("delivery-c")).isZero();
    assertThat(partCount("delivery-a")).isZero();
    assertThat(partCount("delivery-b")).isZero();
    assertThat(partCount("delivery-c")).isOne();
    assertThat(eventSequences()).containsExactly(2L);
    assertThat(deliveryCount()).isEqualTo(3);
  }

  @Test
  void cleanupHonorsBatchAndCapacityCountsOnlyActivePayload() throws Exception {
    createResolved("update-0", 0, "delivery-a", DeliveryAttemptOutcome.SUCCEEDED);
    createResolved("update-1", 1, "delivery-b", DeliveryAttemptOutcome.UNKNOWN);
    ageRows();

    ChannelLedgerResult.Cleanup first = cleanup(1, 10, 1);
    ChannelLedgerResult.Cleanup second = cleanup(1, 10, 1);

    assertThat(first.processed()).isOne();
    assertThat(second.processed()).isOne();
    assertThat(second.capacityAvailable()).isFalse();
    assertThat(cleanup(10, 10, 2).capacityAvailable()).isTrue();
    assertThat(deliveryCount()).isEqualTo(2);
    assertThat(payloadPruned("delivery-a")).isOne();
    assertThat(payloadPruned("delivery-b")).isZero();
  }

  @Test
  void terminalReplaySurvivesResolvedPayloadPruning() throws Exception {
    runningTurn("update-0", 0, "message-0", "turn-0");
    DeliveryEnvelope delivery = terminal("delivery-a", "turn-0", "terminal-payload");
    ledger.recordTerminal(
        new ChannelLedgerCommand.RecordTerminal(INSTANCE, "turn-0", 1, delivery, NOW));
    ChannelLedgerResult.DeliveryWork work = claim(NOW).orElseThrow();
    ledger.recordDeliveryOutcome(
        outcome(work, DeliveryAttemptOutcome.SUCCEEDED, "501", null, "", NOW.plusSeconds(1)));
    ageRows();

    assertThat(cleanup(10, 100, 100).processed()).isOne();
    assertThat(partCount("delivery-a")).isZero();

    assertThat(
            ledger.recordTerminal(
                new ChannelLedgerCommand.RecordTerminal(INSTANCE, "turn-0", 1, delivery, NOW)))
        .isEqualTo(
            new ChannelLedgerResult.Terminal(
                ChannelLedgerResult.TerminalStatus.REPLAYED, "delivery-a", 2));
  }

  @Test
  void recoveryAndCleanupFaultsRollbackWithoutPartialState() throws Exception {
    ledger.recordEvent(feedback("update-0", 0, "delivery-a", "payload"));
    ChannelLedgerResult.DeliveryWork work = claim(NOW).orElseThrow();
    var recoveryHits = new AtomicInteger();
    var recoveryFault =
        new JdbcChannelLedger(
            schema,
            point -> {
              if (point == ChannelLedgerFaultPoint.RECOVERY_BEFORE_COMMIT) {
                recoveryHits.incrementAndGet();
                throw new SQLException("sensitive-recovery-failure");
              }
            });

    assertThatThrownBy(
            () ->
                recoveryFault.recover(
                    new ChannelLedgerCommand.Recover(INSTANCE, OWNER_B, NOW.plusSeconds(10), 10)))
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessageNotContaining("sensitive-recovery-failure");
    assertThat(recoveryHits).hasValue(1);
    assertThat(deliveryState("delivery-a")).isEqualTo("DELIVERING:" + OWNER_A + ":null");
    assertThat(partState("delivery-a")).isEqualTo("IN_FLIGHT:null");
    assertThat(attemptState("delivery-a")).isEqualTo("STARTED:null:null");

    ledger.recordDeliveryOutcome(
        outcome(work, DeliveryAttemptOutcome.SUCCEEDED, "501", null, "", NOW.plusSeconds(20)));
    ageRows();
    var cleanupHits = new AtomicInteger();
    var cleanupFault =
        new JdbcChannelLedger(
            schema,
            point -> {
              if (point == ChannelLedgerFaultPoint.CLEANUP_BEFORE_COMMIT) {
                cleanupHits.incrementAndGet();
                throw new SQLException("sensitive-cleanup-failure");
              }
            });

    assertThatThrownBy(
            () ->
                cleanupFault.cleanup(
                    new ChannelLedgerCommand.Cleanup(INSTANCE, CUTOFF, NOW, 10, 100, 100)))
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessageNotContaining("sensitive-cleanup-failure");
    assertThat(cleanupHits).hasValue(1);
    assertThat(payloadPruned("delivery-a")).isZero();
    assertThat(partCount("delivery-a")).isOne();
  }

  private ChannelLedgerResult.Recovery recover(int batchSize) {
    return ledger.recover(
        new ChannelLedgerCommand.Recover(INSTANCE, OWNER_B, NOW.plusSeconds(10), batchSize));
  }

  private ChannelLedgerResult.Cleanup cleanup(int batchSize, int maxInbox, int maxDelivery) {
    return ledger.cleanup(
        new ChannelLedgerCommand.Cleanup(INSTANCE, CUTOFF, NOW, batchSize, maxInbox, maxDelivery));
  }

  private void runningTurn(String eventId, long sequence, String messageId, String turnId) {
    reserveTurn(eventId, sequence, messageId, turnId);
    ledger.startTurn(
        new ChannelLedgerCommand.StartTurn(
            INSTANCE, eventId, messageId, turnId, OWNER_A, 0, NOW.plusSeconds(60), NOW));
  }

  private void reserveTurn(String eventId, long sequence, String messageId, String turnId) {
    String eventFingerprint =
        ChannelFingerprint.event(INSTANCE, eventId, sequence, InboxEventKind.TURN, "", REQUEST);
    ledger.recordEvent(
        new ChannelLedgerCommand.RecordEvent(
            INSTANCE,
            eventId,
            sequence,
            eventFingerprint,
            InboxEventKind.TURN,
            "",
            REQUEST,
            new ChannelLedgerCommand.TurnReservation(messageId, REQUEST, turnId),
            null,
            NOW));
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

  private DeliveryEnvelope terminal(String deliveryId, String turnId, String... payloads) {
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
        java.util.List.of(payloads));
  }

  private java.util.Optional<ChannelLedgerResult.DeliveryWork> claim(Instant at) {
    return ledger.claimNextDelivery(
        new ChannelLedgerCommand.ClaimDelivery(INSTANCE, OWNER_A, at.plusSeconds(60), at));
  }

  private ChannelLedgerCommand.RecordDeliveryOutcome outcome(
      ChannelLedgerResult.DeliveryWork work,
      DeliveryAttemptOutcome outcome,
      String remoteId,
      Instant retryAt,
      String errorCode,
      Instant completedAt) {
    return new ChannelLedgerCommand.RecordDeliveryOutcome(
        work.deliveryId(),
        work.partIndex(),
        work.attemptNumber(),
        work.ownerId(),
        outcome,
        remoteId,
        retryAt,
        errorCode,
        completedAt);
  }

  private void createResolved(
      String eventId, long sequence, String deliveryId, DeliveryAttemptOutcome outcome) {
    ledger.recordEvent(feedback(eventId, sequence, deliveryId, deliveryId));
    ChannelLedgerResult.DeliveryWork work = claim(NOW.plusSeconds(sequence)).orElseThrow();
    switch (outcome) {
      case SUCCEEDED ->
          ledger.recordDeliveryOutcome(
              outcome(work, outcome, "501", null, "", NOW.plusSeconds(sequence + 1)));
      case PERMANENT_REJECTED ->
          ledger.recordDeliveryOutcome(
              outcome(work, outcome, null, null, "CHAT_NOT_FOUND", NOW.plusSeconds(sequence + 1)));
      case UNKNOWN ->
          ledger.recordDeliveryOutcome(
              outcome(work, outcome, null, null, "SEND_TIMEOUT", NOW.plusSeconds(sequence + 1)));
      case RETRYABLE_REJECTED, STARTED -> throw new IllegalArgumentException("unsupported");
    }
  }

  private void ageRows() throws Exception {
    try (var connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE channel_deliveries SET created_at = '" + OLD + "', updated_at = '" + OLD + "'");
      statement.executeUpdate("UPDATE channel_delivery_parts SET updated_at = '" + OLD + "'");
      statement.executeUpdate(
          "UPDATE channel_inbox_events SET created_at = '" + OLD + "', updated_at = '" + OLD + "'");
    }
  }

  private java.util.List<String> claimStates() throws Exception {
    try (var connection = schema.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT turn_id, state, start_attempts"
                        + " FROM channel_turn_claims ORDER BY turn_id")) {
      var values = new ArrayList<String>();
      while (rows.next()) {
        values.add(rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getInt(3));
      }
      return values;
    }
  }

  private String deliveryState(String id) throws Exception {
    return one(
        "SELECT state || ':' || COALESCE(owner_id, 'null') || ':'"
            + " || COALESCE(last_error_code, 'null') FROM channel_deliveries WHERE delivery_id = ?",
        id);
  }

  private String partState(String id) throws Exception {
    return one(
        "SELECT state || ':' || COALESCE(last_error_code, 'null')"
            + " FROM channel_delivery_parts WHERE delivery_id = ?",
        id);
  }

  private String attemptState(String id) throws Exception {
    return one(
        "SELECT outcome || ':' || COALESCE(completed_at, 'null') || ':'"
            + " || COALESCE(error_code, 'null')"
            + " FROM channel_delivery_attempts WHERE delivery_id = ?",
        id);
  }

  private int payloadPruned(String id) throws Exception {
    return Integer.parseInt(
        one("SELECT payload_pruned FROM channel_deliveries WHERE delivery_id = ?", id));
  }

  private int partCount(String id) throws Exception {
    return Integer.parseInt(
        one("SELECT COUNT(*) FROM channel_delivery_parts WHERE delivery_id = ?", id));
  }

  private long deliveryCount() throws Exception {
    return Long.parseLong(
        one("SELECT COUNT(*) FROM channel_deliveries WHERE delivery_id <> ?", ""));
  }

  private java.util.List<Long> eventSequences() throws Exception {
    try (var connection = schema.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT external_sequence FROM channel_inbox_events ORDER BY external_sequence")) {
      var values = new ArrayList<Long>();
      while (rows.next()) {
        values.add(rows.getLong(1));
      }
      return values;
    }
  }

  private String one(String sql, String id) throws Exception {
    try (var connection = schema.openConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, id);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        String value = rows.getString(1);
        assertThat(rows.next()).isFalse();
        return value;
      }
    }
  }
}
