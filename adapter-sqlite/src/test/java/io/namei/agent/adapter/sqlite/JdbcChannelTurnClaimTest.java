package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.reliability.ChannelFingerprint;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.InboxEventKind;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcChannelTurnClaimTest {
  private static final Instant NOW = Instant.parse("2026-07-16T03:00:00Z");
  private static final Instant LEASE = Instant.parse("2026-07-16T03:05:00Z");
  private static final ChannelInstanceId INSTANCE = ChannelInstanceId.derive("telegram", "100001");
  private static final String REQUEST_A = "a".repeat(64);
  private static final String REQUEST_B = "b".repeat(64);
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
  void reservesClaimWithoutAdvancingCursorAndReusesOriginalTurn() throws Exception {
    var first = turnEvent("update-0", 0, "message-1", REQUEST_A, "turn-original");

    ChannelLedgerResult.Event reserved = ledger.recordEvent(first);
    ChannelLedgerResult.Event replay = ledger.recordEvent(first);
    ChannelLedgerResult.Event logicalDuplicate =
        ledger.recordEvent(turnEvent("update-1", 1, "message-1", REQUEST_A, "turn-recreated"));

    assertThat(reserved)
        .isEqualTo(
            new ChannelLedgerResult.Event(
                ChannelLedgerResult.InboxStatus.RESERVED_NEW, "turn-original", 0, null, 0));
    assertThat(replay.status()).isEqualTo(ChannelLedgerResult.InboxStatus.START_RETRYABLE);
    assertThat(logicalDuplicate.status())
        .isEqualTo(ChannelLedgerResult.InboxStatus.START_RETRYABLE);
    assertThat(logicalDuplicate.turnId()).isEqualTo("turn-original");
    assertThat(logicalDuplicate.revision()).isZero();
    assertThat(count("channel_turn_claims")).isOne();
    assertThat(count("channel_inbox_events")).isEqualTo(2);
    assertThat(count("channel_cursors")).isZero();
    assertThat(claim("message-1")).isEqualTo("turn-original:RESERVED:0:0:null:null");
  }

  @Test
  void rejectsRequestFingerprintAndTurnIdConflictsWithoutNewEvent() throws Exception {
    ledger.recordEvent(turnEvent("update-0", 0, "message-1", REQUEST_A, "turn-1"));

    assertConflict(turnEvent("update-1", 1, "message-1", REQUEST_B, "turn-2"));
    assertConflict(turnEvent("update-2", 2, "message-2", REQUEST_A, "turn-1"));

    assertThat(count("channel_turn_claims")).isOne();
    assertThat(count("channel_inbox_events")).isOne();
    assertThat(count("channel_cursors")).isZero();
  }

  @Test
  void concurrentLogicalMessageCreatesOneClaimAndKeepsOneTurnId() throws Exception {
    var first = turnEvent("update-a", 0, "message-1", REQUEST_A, "turn-a");
    var second = turnEvent("update-b", 1, "message-1", REQUEST_A, "turn-b");
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var results = new ArrayList<ChannelLedgerResult.Event>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstFuture = executor.submit(() -> invokeAfterBarrier(first, ready, start));
      var secondFuture = executor.submit(() -> invokeAfterBarrier(second, ready, start));
      ready.await();
      start.countDown();
      results.add(firstFuture.get());
      results.add(secondFuture.get());
    }

    assertThat(results.stream().map(ChannelLedgerResult.Event::status))
        .containsExactlyInAnyOrder(
            ChannelLedgerResult.InboxStatus.RESERVED_NEW,
            ChannelLedgerResult.InboxStatus.START_RETRYABLE);
    assertThat(results.stream().map(ChannelLedgerResult.Event::turnId).distinct()).hasSize(1);
    assertThat(count("channel_turn_claims")).isOne();
    assertThat(count("channel_inbox_events")).isEqualTo(2);
    assertThat(count("channel_cursors")).isZero();
  }

  @Test
  void startsTurnAndAdvancesCursorInOneTransaction() throws Exception {
    ledger.recordEvent(turnEvent("update-7", 7, "message-1", REQUEST_A, "turn-1"));

    ChannelLedgerResult.TurnStart started =
        ledger.startTurn(start("update-7", "message-1", "turn-1", OWNER_A, 0));

    assertThat(started)
        .isEqualTo(
            new ChannelLedgerResult.TurnStart(ChannelLedgerResult.TurnStartStatus.STARTED, 1, 8));
    assertThat(claim("message-1")).isEqualTo("turn-1:RUNNING:1:1:" + OWNER_A + ":" + LEASE);
    assertThat(cursor()).isEqualTo("8:0");

    ChannelLedgerResult.TurnStart duplicate =
        ledger.startTurn(start("update-7", "message-1", "turn-1", OWNER_B, 1));
    assertThat(duplicate.status()).isEqualTo(ChannelLedgerResult.TurnStartStatus.STALE);
    assertThat(claim("message-1")).contains(":" + OWNER_A + ":");
  }

  @Test
  void staleRevisionOrIdentityCannotCrossExecutionBoundary() throws Exception {
    ledger.recordEvent(turnEvent("update-0", 0, "message-1", REQUEST_A, "turn-1"));

    ChannelLedgerResult.TurnStart staleRevision =
        ledger.startTurn(start("update-0", "message-1", "turn-1", OWNER_A, 1));
    ChannelLedgerResult.TurnStart staleIdentity =
        ledger.startTurn(start("update-0", "message-1", "other-turn", OWNER_A, 0));

    assertThat(staleRevision.status()).isEqualTo(ChannelLedgerResult.TurnStartStatus.STALE);
    assertThat(staleIdentity.status()).isEqualTo(ChannelLedgerResult.TurnStartStatus.STALE);
    assertThat(claim("message-1")).isEqualTo("turn-1:RESERVED:0:0:null:null");
    assertThat(count("channel_cursors")).isZero();
  }

  @Test
  void staleCursorDoesNotMutateRetryableClaim() throws Exception {
    var event = turnEvent("update-0", 0, "message-1", REQUEST_A, "turn-1");
    ledger.recordEvent(event);
    ledger.recover(new ChannelLedgerCommand.Recover(INSTANCE, OWNER_A, NOW.plusSeconds(1), 1));
    try (var connection = schema.openConnection();
        var insert =
            connection.prepareStatement(
                """
                INSERT INTO channel_cursors (
                  channel, instance_id, next_sequence, revision, updated_at
                ) VALUES (?, ?, 1, 0, ?)
                """)) {
      insert.setString(1, INSTANCE.channel());
      insert.setString(2, INSTANCE.value());
      insert.setString(3, NOW.toString());
      insert.executeUpdate();
    }

    ChannelLedgerResult.TurnStart stale =
        ledger.startTurn(start("update-0", "message-1", "turn-1", OWNER_A, 1));

    assertThat(stale)
        .isEqualTo(
            new ChannelLedgerResult.TurnStart(ChannelLedgerResult.TurnStartStatus.STALE, 1, 1));
    assertThat(claim("message-1")).isEqualTo("turn-1:START_RETRYABLE:1:1:null:null");
  }

  @Test
  void databaseFaultBeforeStartCommitKeepsAgentInvocationAtZero() throws Exception {
    var invocations = new AtomicInteger();
    var faulting =
        new JdbcChannelLedger(
            schema,
            point -> {
              if (point == ChannelLedgerFaultPoint.TURN_STARTED_BEFORE_COMMIT) {
                throw new SQLException("sensitive-start-commit-failure");
              }
            });
    faulting.recordEvent(turnEvent("update-0", 0, "message-1", REQUEST_A, "turn-1"));

    assertThatThrownBy(
            () -> {
              faulting.startTurn(start("update-0", "message-1", "turn-1", OWNER_A, 0));
              invocations.incrementAndGet();
            })
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessage("渠道账本操作失败")
        .hasMessageNotContaining("sensitive-start-commit-failure");

    assertThat(invocations).hasValue(0);
    assertThat(claim("message-1")).isEqualTo("turn-1:RESERVED:0:0:null:null");
    assertThat(count("channel_cursors")).isZero();
  }

  @Test
  void recoveryRetriesReservedButNeverRerunsRunningClaim() throws Exception {
    var running = turnEvent("update-0", 0, "message-running", REQUEST_A, "turn-running");
    var reserved = turnEvent("update-1", 1, "message-reserved", REQUEST_B, "turn-reserved");
    ledger.recordEvent(running);
    ledger.startTurn(start("update-0", "message-running", "turn-running", OWNER_A, 0));
    ledger.recordEvent(reserved);

    ChannelLedgerResult.Recovery recovered =
        ledger.recover(
            new ChannelLedgerCommand.Recover(INSTANCE, OWNER_B, NOW.plusSeconds(10), 10));

    assertThat(recovered).isEqualTo(new ChannelLedgerResult.Recovery(2, false));
    assertThat(claim("message-running")).isEqualTo("turn-running:EXECUTION_UNKNOWN:1:2:null:null");
    assertThat(claim("message-reserved")).isEqualTo("turn-reserved:START_RETRYABLE:1:1:null:null");
    assertThat(ledger.recordEvent(running).status())
        .isEqualTo(ChannelLedgerResult.InboxStatus.EXECUTION_UNKNOWN);
    assertThat(ledger.recordEvent(reserved).status())
        .isEqualTo(ChannelLedgerResult.InboxStatus.START_RETRYABLE);
    assertThat(
            ledger
                .startTurn(start("update-0", "message-running", "turn-running", OWNER_B, 2))
                .status())
        .isEqualTo(ChannelLedgerResult.TurnStartStatus.STALE);
  }

  @Test
  void explicitStarterFailuresReuseTurnAndStopAfterThreeAttempts() throws Exception {
    var event = turnEvent("update-0", 0, "message-1", REQUEST_A, "turn-original");
    ledger.recordEvent(event);

    ledger.recover(new ChannelLedgerCommand.Recover(INSTANCE, OWNER_A, NOW.plusSeconds(1), 1));
    ChannelLedgerResult.Event secondAttempt = ledger.recordEvent(event);
    ledger.recover(new ChannelLedgerCommand.Recover(INSTANCE, OWNER_B, NOW.plusSeconds(2), 1));
    ChannelLedgerResult.Event thirdAttempt = ledger.recordEvent(event);
    ledger.recover(new ChannelLedgerCommand.Recover(INSTANCE, OWNER_A, NOW.plusSeconds(3), 1));
    ChannelLedgerResult.Event exhausted = ledger.recordEvent(event);

    assertThat(secondAttempt.status()).isEqualTo(ChannelLedgerResult.InboxStatus.START_RETRYABLE);
    assertThat(thirdAttempt.status()).isEqualTo(ChannelLedgerResult.InboxStatus.START_RETRYABLE);
    assertThat(exhausted.status()).isEqualTo(ChannelLedgerResult.InboxStatus.EXECUTION_UNKNOWN);
    assertThat(exhausted.turnId()).isEqualTo("turn-original");
    assertThat(claim("message-1")).isEqualTo("turn-original:EXECUTION_UNKNOWN:3:6:null:null");
    assertThat(count("channel_cursors")).isZero();
  }

  @Test
  void exhaustedStartBudgetBecomesExecutionUnknownWithoutCursorAdvance() throws Exception {
    ledger.recordEvent(turnEvent("update-0", 0, "message-1", REQUEST_A, "turn-1"));
    try (var connection = schema.openConnection()) {
      connection
          .createStatement()
          .executeUpdate(
              "UPDATE channel_turn_claims"
                  + " SET state = 'START_RETRYABLE', start_attempts = 3"
                  + " WHERE external_message_id = 'message-1'");
    }

    ChannelLedgerResult.TurnStart exhausted =
        ledger.startTurn(start("update-0", "message-1", "turn-1", OWNER_A, 0));

    assertThat(exhausted)
        .isEqualTo(
            new ChannelLedgerResult.TurnStart(ChannelLedgerResult.TurnStartStatus.STALE, 1, 0));
    assertThat(claim("message-1")).isEqualTo("turn-1:EXECUTION_UNKNOWN:3:1:null:null");
    assertThat(count("channel_cursors")).isZero();
  }

  private ChannelLedgerResult.Event invokeAfterBarrier(
      ChannelLedgerCommand.RecordEvent command, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      start.await();
      return ledger.recordEvent(command);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private void assertConflict(ChannelLedgerCommand.RecordEvent command) {
    assertThatThrownBy(() -> ledger.recordEvent(command))
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.IDEMPOTENCY_CONFLICT);
  }

  private ChannelLedgerCommand.RecordEvent turnEvent(
      String eventId,
      long sequence,
      String externalMessageId,
      String requestFingerprint,
      String turnId) {
    String eventFingerprint =
        ChannelFingerprint.event(
            INSTANCE, eventId, sequence, InboxEventKind.TURN, "", requestFingerprint);
    return new ChannelLedgerCommand.RecordEvent(
        INSTANCE,
        eventId,
        sequence,
        eventFingerprint,
        InboxEventKind.TURN,
        "",
        requestFingerprint,
        new ChannelLedgerCommand.TurnReservation(externalMessageId, requestFingerprint, turnId),
        null,
        NOW);
  }

  private ChannelLedgerCommand.StartTurn start(
      String eventId, String messageId, String turnId, String owner, long revision) {
    return new ChannelLedgerCommand.StartTurn(
        INSTANCE, eventId, messageId, turnId, owner, revision, LEASE, NOW);
  }

  private long count(String table) throws Exception {
    try (var connection = schema.openConnection();
        var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertThat(rows.next()).isTrue();
      long count = rows.getLong(1);
      assertThat(rows.next()).isFalse();
      return count;
    }
  }

  private String cursor() throws Exception {
    try (var connection = schema.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery("SELECT next_sequence, revision FROM channel_cursors")) {
      assertThat(rows.next()).isTrue();
      String value = rows.getLong(1) + ":" + rows.getLong(2);
      assertThat(rows.next()).isFalse();
      return value;
    }
  }

  private String claim(String externalMessageId) throws Exception {
    try (var connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT turn_id, state, start_attempts, revision, owner_id, lease_expires_at
                FROM channel_turn_claims
                WHERE channel = ? AND instance_id = ? AND external_message_id = ?
                """)) {
      statement.setString(1, INSTANCE.channel());
      statement.setString(2, INSTANCE.value());
      statement.setString(3, externalMessageId);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        String value =
            rows.getString("turn_id")
                + ":"
                + rows.getString("state")
                + ":"
                + rows.getInt("start_attempts")
                + ":"
                + rows.getLong("revision")
                + ":"
                + rows.getString("owner_id")
                + ":"
                + rows.getString("lease_expires_at");
        assertThat(rows.next()).isFalse();
        return value;
      }
    }
  }
}
