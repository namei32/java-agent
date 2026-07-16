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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcChannelLedgerInboxTest {
  private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");
  private static final ChannelInstanceId INSTANCE = ChannelInstanceId.derive("telegram", "100001");

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
  void recordsIgnoredAndControlEventsWithMonotonicCursor() throws Exception {
    ChannelLedgerResult.Event ignored = ledger.recordEvent(event("update-3", 3, "UNAUTHORIZED"));
    ChannelLedgerResult.Event control =
        ledger.recordEvent(event("update-9", 9, InboxEventKind.CONTROL, "CANCEL_REQUESTED"));

    assertThat(ignored)
        .isEqualTo(
            new ChannelLedgerResult.Event(
                ChannelLedgerResult.InboxStatus.EVENT_RECORDED, null, 0, null, 4));
    assertThat(control)
        .isEqualTo(
            new ChannelLedgerResult.Event(
                ChannelLedgerResult.InboxStatus.EVENT_RECORDED, null, 1, null, 10));
    assertThat(ledger.snapshot(INSTANCE).nextSequence()).isEqualTo(10);
    try (var connection = schema.openConnection();
        var events =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT external_event_id, external_sequence, decision, turn_id"
                        + " FROM channel_inbox_events ORDER BY external_sequence")) {
      assertThat(readRows(events))
          .containsExactly("update-3:3:IGNORED:null", "update-9:9:CONTROL:null");
    }
  }

  @Test
  void matchingReplayIsReadOnlyAndReturnsCommittedCursor() throws Exception {
    var command = event("update-7", 7, "UNAUTHORIZED");

    ChannelLedgerResult.Event first = ledger.recordEvent(command);
    ChannelLedgerResult.Event replay = ledger.recordEvent(command);

    assertThat(replay).isEqualTo(first);
    assertThat(count("channel_inbox_events")).isOne();
    assertThat(cursor()).isEqualTo("8:0");
  }

  @Test
  void conflictingIdSequenceOrLowerUnrecordedSequenceFailsClosed() throws Exception {
    ledger.recordEvent(event("update-10", 10, "UNAUTHORIZED"));

    assertConflict(event("update-10", 10, "UNSUPPORTED_MESSAGE"));
    assertConflict(event("different-id", 10, "UNAUTHORIZED"));
    assertConflict(event("late-update-4", 4, "UNAUTHORIZED"));

    assertThat(count("channel_inbox_events")).isOne();
    assertThat(cursor()).isEqualTo("11:0");
  }

  @Test
  void concurrentSameSequenceHasOneWinnerAndNoPartialRows() throws Exception {
    var first = event("update-a", 0, "UNAUTHORIZED");
    var second = event("update-b", 0, "UNAUTHORIZED");
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var outcomes = new ArrayList<Object>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstFuture = executor.submit(() -> invokeAfterBarrier(first, ready, start));
      var secondFuture = executor.submit(() -> invokeAfterBarrier(second, ready, start));
      ready.await();
      start.countDown();
      outcomes.add(firstFuture.get());
      outcomes.add(secondFuture.get());
    }

    assertThat(outcomes.stream().filter(ChannelLedgerResult.Event.class::isInstance)).hasSize(1);
    assertThat(
            outcomes.stream()
                .filter(ChannelLedgerRepositoryException.class::isInstance)
                .map(ChannelLedgerRepositoryException.class::cast)
                .map(ChannelLedgerRepositoryException::failure))
        .containsExactly(ChannelLedgerRepositoryFailure.IDEMPOTENCY_CONFLICT);
    assertThat(count("channel_inbox_events")).isOne();
    assertThat(cursor()).isEqualTo("1:0");
  }

  @Test
  void faultBeforeCommitRollsBackEventAndCursor() throws Exception {
    var faulting =
        new JdbcChannelLedger(
            schema,
            point -> {
              if (point == ChannelLedgerFaultPoint.EVENT_RECORDED_BEFORE_COMMIT) {
                throw new SQLException("sensitive-database-failure");
              }
            });

    assertThatThrownBy(() -> faulting.recordEvent(event("update-0", 0, "UNAUTHORIZED")))
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessage("渠道账本操作失败")
        .hasMessageNotContaining("sensitive-database-failure")
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.OPERATION_FAILED);

    assertThat(count("channel_inbox_events")).isZero();
    assertThat(count("channel_cursors")).isZero();
    assertThat(ledger.snapshot(INSTANCE).nextSequence()).isZero();
  }

  @Test
  void commandRejectsSequenceThatCannotAdvance() {
    assertThatThrownBy(() -> event("overflow", Long.MAX_VALUE, "UNAUTHORIZED"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Object invokeAfterBarrier(
      ChannelLedgerCommand.RecordEvent command, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      start.await();
      return ledger.recordEvent(command);
    } catch (ChannelLedgerRepositoryException exception) {
      return exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private void assertConflict(ChannelLedgerCommand.RecordEvent command) {
    assertThatThrownBy(() -> ledger.recordEvent(command))
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessage("渠道账本幂等冲突")
        .hasMessageNotContaining(command.externalEventId())
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.IDEMPOTENCY_CONFLICT);
  }

  private ChannelLedgerCommand.RecordEvent event(String id, long sequence, String code) {
    return event(id, sequence, InboxEventKind.IGNORED, code);
  }

  private ChannelLedgerCommand.RecordEvent event(
      String id, long sequence, InboxEventKind kind, String code) {
    String fingerprint = ChannelFingerprint.event(INSTANCE, id, sequence, kind, code, "");
    return new ChannelLedgerCommand.RecordEvent(
        INSTANCE, id, sequence, fingerprint, kind, code, "", null, null, NOW);
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
        var statement =
            connection.prepareStatement(
                "SELECT next_sequence, revision FROM channel_cursors"
                    + " WHERE channel = ? AND instance_id = ?")) {
      statement.setString(1, INSTANCE.channel());
      statement.setString(2, INSTANCE.value());
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        String value = rows.getLong(1) + ":" + rows.getLong(2);
        assertThat(rows.next()).isFalse();
        return value;
      }
    }
  }

  private static java.util.List<String> readRows(java.sql.ResultSet rows) throws Exception {
    var values = new ArrayList<String>();
    while (rows.next()) {
      values.add(
          rows.getString("external_event_id")
              + ":"
              + rows.getLong("external_sequence")
              + ":"
              + rows.getString("decision")
              + ":"
              + rows.getString("turn_id"));
    }
    return values;
  }
}
