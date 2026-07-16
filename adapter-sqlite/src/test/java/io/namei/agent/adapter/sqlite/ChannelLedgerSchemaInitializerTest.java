package io.namei.agent.adapter.sqlite;

import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.columns;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.createV0;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.pragmaInt;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.pragmaText;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.quickCheck;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.rawConnection;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.schemaTimestamp;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.schemaVersion;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.tables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;

class ChannelLedgerSchemaInitializerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), ZoneOffset.UTC);
  private static final UUID BACKUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

  @TempDir Path tempDir;

  @Test
  void createsExactV1SchemaWithRequiredPragmas() throws Exception {
    var initializer = new ChannelLedgerSchemaInitializer(database(), 5_000);

    initializer.initialize();

    assertThat(database()).isRegularFile();
    try (var connection = initializer.openConnection()) {
      assertThat(tables(connection))
          .containsExactlyInAnyOrder(
              "channel_schema",
              "channel_cursors",
              "channel_inbox_events",
              "channel_turn_claims",
              "channel_deliveries",
              "channel_delivery_parts",
              "channel_delivery_attempts");
      assertThat(columns(connection, "channel_schema"))
          .containsExactly("singleton", "version", "updated_at");
      assertThat(columns(connection, "channel_cursors"))
          .containsExactly("channel", "instance_id", "next_sequence", "revision", "updated_at");
      assertThat(columns(connection, "channel_inbox_events"))
          .containsExactly(
              "channel",
              "instance_id",
              "external_event_id",
              "external_sequence",
              "event_fingerprint",
              "decision",
              "turn_id",
              "created_at",
              "updated_at");
      assertThat(columns(connection, "channel_turn_claims"))
          .containsExactly(
              "channel",
              "instance_id",
              "external_message_id",
              "request_fingerprint",
              "turn_id",
              "state",
              "start_attempts",
              "owner_id",
              "lease_expires_at",
              "revision",
              "created_at",
              "updated_at");
      assertThat(columns(connection, "channel_deliveries"))
          .containsExactly(
              "delivery_id",
              "channel",
              "instance_id",
              "target_id",
              "source_kind",
              "correlation_id",
              "message_type",
              "payload_fingerprint",
              "state",
              "part_count",
              "next_part_index",
              "payload_pruned",
              "owner_id",
              "lease_expires_at",
              "last_error_code",
              "revision",
              "created_at",
              "updated_at");
      assertThat(columns(connection, "channel_delivery_parts"))
          .containsExactly(
              "delivery_id",
              "part_index",
              "payload_text",
              "payload_fingerprint",
              "state",
              "attempt_count",
              "next_attempt_at",
              "remote_message_id",
              "last_error_code",
              "updated_at");
      assertThat(columns(connection, "channel_delivery_attempts"))
          .containsExactly(
              "delivery_id",
              "part_index",
              "attempt_number",
              "started_at",
              "completed_at",
              "outcome",
              "remote_message_id",
              "error_code");
      assertThat(schemaVersion(connection)).isEqualTo(1);
      assertThat(pragmaText(connection, "journal_mode")).isEqualToIgnoringCase("wal");
      assertThat(pragmaInt(connection, "synchronous")).isEqualTo(2);
      assertThat(pragmaInt(connection, "foreign_keys")).isEqualTo(1);
      assertThat(pragmaInt(connection, "busy_timeout")).isEqualTo(5_000);
      assertThat(quickCheck(connection)).isEqualToIgnoringCase("ok");
    }
    assertThatThrownBy(
            () ->
                new ChannelLedgerSchemaInitializer(database().resolveSibling("sessions.db"), 5_000))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void repeatedInitializationPreservesTimestampAndData() throws Exception {
    var initializer = new ChannelLedgerSchemaInitializer(database(), 5_000);
    initializer.initialize();
    try (var connection = initializer.openConnection();
        var insert =
            connection.prepareStatement(
                """
                INSERT INTO channel_cursors (
                  channel, instance_id, next_sequence, revision, updated_at
                ) VALUES (?, ?, 501, 1, ?)
                """)) {
      insert.setString(1, "telegram");
      insert.setString(2, "a".repeat(64));
      insert.setString(3, "2026-07-16T01:00:00Z");
      insert.executeUpdate();
    }
    String timestamp;
    try (var connection = initializer.openConnection()) {
      timestamp = schemaTimestamp(connection);
    }

    initializer.initialize();

    try (var connection = initializer.openConnection();
        var rows =
            connection
                .createStatement()
                .executeQuery("SELECT next_sequence FROM channel_cursors")) {
      assertThat(schemaTimestamp(connection)).isEqualTo(timestamp);
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong(1)).isEqualTo(501);
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void backsUpV0BeforeChangingJournalModeOrSchema() throws Exception {
    createV0(database());
    ChannelLedgerBackup observingBackup =
        (source, destination) -> {
          assertThat(pragmaText(source, "journal_mode")).isEqualToIgnoringCase("delete");
          assertThat(tables(source)).containsExactly("channel_schema");
          sqliteBackup(source, destination);
        };
    var initializer =
        new ChannelLedgerSchemaInitializer(
            database(), 5_000, CLOCK, observingBackup, () -> BACKUP_ID);

    initializer.initialize();

    try (var connection = initializer.openConnection()) {
      assertThat(schemaVersion(connection)).isEqualTo(1);
      assertThat(pragmaText(connection, "journal_mode")).isEqualToIgnoringCase("wal");
    }
    Path backup = database().resolveSibling("channel-ledger.db.v0-to-v1-" + BACKUP_ID + ".bak");
    assertThat(backup).isRegularFile();
    try (var connection = rawConnection(backup)) {
      assertThat(quickCheck(connection)).isEqualTo("ok");
      assertThat(schemaVersion(connection)).isZero();
      assertThat(tables(connection)).containsExactly("channel_schema");
      assertThat(pragmaText(connection, "journal_mode")).isEqualToIgnoringCase("delete");
    }
  }

  @Test
  void backupFailureLeavesV0UnchangedAndRemovesPartialFile() throws Exception {
    createV0(database());
    var initializer =
        new ChannelLedgerSchemaInitializer(
            database(),
            5_000,
            CLOCK,
            (source, destination) -> {
              Files.writeString(destination, "partial");
              throw new SQLException("sensitive-sqlite-message");
            },
            () -> BACKUP_ID);

    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessage("渠道账本备份失败")
        .hasMessageNotContaining("sensitive-sqlite-message")
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.BACKUP_FAILED);

    try (var connection = rawConnection(database())) {
      assertThat(schemaVersion(connection)).isZero();
      assertThat(schemaTimestamp(connection)).isEqualTo("2026-07-16T00:00:00Z");
      assertThat(tables(connection)).containsExactly("channel_schema");
      assertThat(pragmaText(connection, "journal_mode")).isEqualToIgnoringCase("delete");
    }
    assertThat(database().resolveSibling("channel-ledger.db.v0-to-v1-" + BACKUP_ID + ".bak"))
        .doesNotExist();
  }

  @Test
  void rejectsFutureUnknownAndJournalDriftWithoutRepairingThem() throws Exception {
    var initializer = new ChannelLedgerSchemaInitializer(database(), 5_000);
    initializer.initialize();
    try (var connection = initializer.openConnection()) {
      connection.createStatement().executeUpdate("UPDATE channel_schema SET version = 2");
    }

    assertSchemaIncompatible(initializer);
    try (var connection = rawConnection(database())) {
      assertThat(schemaVersion(connection)).isEqualTo(2);
    }

    try (var connection = rawConnection(database())) {
      connection.createStatement().executeUpdate("UPDATE channel_schema SET version = 1");
      connection.createStatement().execute("CREATE VIEW unexpected_view AS SELECT 1");
    }
    assertSchemaIncompatible(initializer);
    try (var connection = rawConnection(database())) {
      assertThat(ChannelLedgerSchemaTestSupport.viewsAndTriggers(connection))
          .containsExactly("view:unexpected_view");
      connection.createStatement().execute("DROP VIEW unexpected_view");
      assertThat(pragmaText(connection, "journal_mode=DELETE")).isEqualToIgnoringCase("delete");
    }
    assertSchemaIncompatible(initializer);
    try (var connection = rawConnection(database())) {
      assertThat(pragmaText(connection, "journal_mode")).isEqualToIgnoringCase("delete");
    }
  }

  @Test
  void rejectsNonIntegerVersionBeforeBackupOrJournalChange() throws Exception {
    createV0(database());
    try (var connection = rawConnection(database())) {
      connection.createStatement().executeUpdate("UPDATE channel_schema SET version = 0.5");
    }
    var backupCalls = new AtomicInteger();
    var initializer =
        new ChannelLedgerSchemaInitializer(
            database(),
            5_000,
            CLOCK,
            (source, destination) -> backupCalls.incrementAndGet(),
            () -> BACKUP_ID);

    assertSchemaIncompatible(initializer);

    assertThat(backupCalls).hasValue(0);
    try (var connection = rawConnection(database())) {
      assertThat(pragmaText(connection, "journal_mode")).isEqualToIgnoringCase("delete");
      try (var rows =
          connection
              .createStatement()
              .executeQuery("SELECT typeof(version), version FROM channel_schema")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("real");
        assertThat(rows.getDouble(2)).isEqualTo(0.5d);
      }
    }
  }

  @Test
  void rejectsCorruptDatabaseWithStableFailureAndNoRewrite() throws Exception {
    Files.createDirectories(database().getParent());
    byte[] corrupt = "not-a-channel-ledger".getBytes(StandardCharsets.UTF_8);
    Files.write(database(), corrupt);

    assertThatThrownBy(() -> new ChannelLedgerSchemaInitializer(database(), 5_000).initialize())
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .hasMessage("渠道账本数据库不可用")
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.DATABASE_UNAVAILABLE);
    assertThat(Files.readAllBytes(database())).containsExactly(corrupt);
  }

  private Path database() {
    return tempDir.resolve("workspace/channels/channel-ledger.db");
  }

  private static void assertSchemaIncompatible(ChannelLedgerSchemaInitializer initializer) {
    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.SCHEMA_INCOMPATIBLE);
  }

  private static void sqliteBackup(java.sql.Connection source, Path destination)
      throws SQLException {
    SQLiteConnection sqlite = source.unwrap(SQLiteConnection.class);
    int result = sqlite.getDatabase().backup("main", destination.toString(), null);
    if (result != SQLiteErrorCode.SQLITE_OK.code) {
      throw new SQLException("backup failed");
    }
  }
}
