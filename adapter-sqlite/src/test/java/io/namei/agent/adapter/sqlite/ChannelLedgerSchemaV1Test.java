package io.namei.agent.adapter.sqlite;

import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.indexColumns;
import static io.namei.agent.adapter.sqlite.ChannelLedgerSchemaTestSupport.indexes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelLedgerSchemaV1Test {
  @TempDir Path tempDir;

  private ChannelLedgerSchemaInitializer initializer;

  @BeforeEach
  void initialize() {
    initializer =
        new ChannelLedgerSchemaInitializer(tempDir.resolve("channels/channel-ledger.db"), 5_000);
    initializer.initialize();
  }

  @Test
  void fixesNamedIndexesAndForeignKeys() throws Exception {
    try (var connection = initializer.openConnection()) {
      assertThat(indexes(connection))
          .containsExactlyInAnyOrder(
              "ix_channel_inbox_turn",
              "ix_channel_claims_recovery",
              "ix_channel_deliveries_due",
              "ix_channel_parts_due",
              "ix_channel_attempts_outcome");
      assertThat(indexColumns(connection, "ix_channel_inbox_turn")).containsExactly("turn_id ASC");
      assertThat(indexColumns(connection, "ix_channel_claims_recovery"))
          .containsExactly(
              "channel ASC", "instance_id ASC", "state ASC", "updated_at ASC", "turn_id ASC");
      assertThat(indexColumns(connection, "ix_channel_deliveries_due"))
          .containsExactly(
              "channel ASC", "instance_id ASC", "state ASC", "created_at ASC", "delivery_id ASC");
      assertThat(indexColumns(connection, "ix_channel_parts_due"))
          .containsExactly("state ASC", "next_attempt_at ASC", "delivery_id ASC", "part_index ASC");
      assertThat(indexColumns(connection, "ix_channel_attempts_outcome"))
          .containsExactly("delivery_id ASC", "outcome ASC");
      assertThat(foreignKeyTargets(connection, "channel_inbox_events"))
          .containsExactly("channel_turn_claims:turn_id->turn_id:RESTRICT:RESTRICT");
      assertThat(foreignKeyTargets(connection, "channel_delivery_parts"))
          .containsExactly("channel_deliveries:delivery_id->delivery_id:CASCADE:RESTRICT");
      assertThat(foreignKeyTargets(connection, "channel_delivery_attempts"))
          .containsExactly(
              "channel_delivery_parts:delivery_id->delivery_id:CASCADE:RESTRICT",
              "channel_delivery_parts:part_index->part_index:CASCADE:RESTRICT");
    }
  }

  @Test
  void databaseChecksRejectInvalidClaimDeliveryPartAndAttemptShapes() throws Exception {
    try (var connection = initializer.openConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      """
                      INSERT INTO channel_turn_claims (
                        channel, instance_id, external_message_id, request_fingerprint, turn_id,
                        state, start_attempts, revision, created_at, updated_at
                      ) VALUES (
                        'telegram', '%s', 'message-1', '%s', 'turn-1',
                        'RUNNING', 1, 0, '2026-07-16T00:00:00Z', '2026-07-16T00:00:00Z'
                      )
                      """
                          .formatted("a".repeat(64), "b".repeat(64))))
          .isInstanceOf(SQLException.class);

      insertDelivery(statement, "delivery-1");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      """
                      INSERT INTO channel_delivery_parts (
                        delivery_id, part_index, payload_text, payload_fingerprint, state,
                        attempt_count, updated_at
                      ) VALUES (
                        'delivery-1', 0, 'payload', '%s', 'RETRY_WAIT', 1,
                        '2026-07-16T00:00:00Z'
                      )
                      """
                          .formatted("c".repeat(64))))
          .isInstanceOf(SQLException.class);

      insertPendingPart(statement, "delivery-1");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      """
                      INSERT INTO channel_delivery_attempts (
                        delivery_id, part_index, attempt_number, started_at, outcome
                      ) VALUES (
                        'delivery-1', 0, 1, '2026-07-16T00:00:00Z', 'SUCCEEDED'
                      )
                      """))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void strictValidatorRejectsMissingNamedIndexWithoutRepair() throws Exception {
    try (var connection = initializer.openConnection()) {
      connection.createStatement().execute("DROP INDEX ix_channel_parts_due");
    }

    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.SCHEMA_INCOMPATIBLE);

    try (var connection = ChannelLedgerSchemaTestSupport.rawConnection(initializer.database())) {
      assertThat(indexes(connection)).doesNotContain("ix_channel_parts_due");
    }
  }

  @Test
  void strictValidatorRejectsUnknownTableColumnAndTriggerWithoutRepair() throws Exception {
    try (var connection = initializer.openConnection()) {
      connection.createStatement().execute("CREATE TABLE unexpected_table (value TEXT)");
    }
    assertSchemaIncompatible(initializer);

    var columnInitializer = initializer("unknown-column");
    try (var connection = columnInitializer.openConnection()) {
      connection
          .createStatement()
          .execute("ALTER TABLE channel_cursors ADD COLUMN unexpected_column TEXT");
    }
    assertSchemaIncompatible(columnInitializer);

    var triggerInitializer = initializer("unknown-trigger");
    try (var connection = triggerInitializer.openConnection()) {
      connection
          .createStatement()
          .execute(
              """
              CREATE TRIGGER unexpected_trigger
              AFTER UPDATE ON channel_cursors
              BEGIN
                SELECT 1;
              END
              """);
    }
    assertSchemaIncompatible(triggerInitializer);
  }

  private ChannelLedgerSchemaInitializer initializer(String directory) {
    var created =
        new ChannelLedgerSchemaInitializer(
            tempDir.resolve(directory + "/channels/channel-ledger.db"), 5_000);
    created.initialize();
    return created;
  }

  private static void assertSchemaIncompatible(ChannelLedgerSchemaInitializer candidate) {
    assertThatThrownBy(candidate::initialize)
        .isInstanceOf(ChannelLedgerRepositoryException.class)
        .extracting(exception -> ((ChannelLedgerRepositoryException) exception).failure())
        .isEqualTo(ChannelLedgerRepositoryFailure.SCHEMA_INCOMPATIBLE);
  }

  private static java.util.List<String> foreignKeyTargets(
      java.sql.Connection connection, String table) throws Exception {
    var rowsById = new java.util.TreeMap<Integer, java.util.List<String>>();
    try (var rows =
        connection.createStatement().executeQuery("PRAGMA foreign_key_list('" + table + "')")) {
      while (rows.next()) {
        int id = rows.getInt("id");
        rowsById
            .computeIfAbsent(id, ignored -> new java.util.ArrayList<>())
            .add(
                rows.getString("table")
                    + ":"
                    + rows.getString("from")
                    + "->"
                    + rows.getString("to")
                    + ":"
                    + rows.getString("on_delete")
                    + ":"
                    + rows.getString("on_update"));
      }
    }
    return rowsById.values().stream().flatMap(java.util.Collection::stream).sorted().toList();
  }

  private static void insertDelivery(java.sql.Statement statement, String id) throws Exception {
    statement.executeUpdate(
        """
        INSERT INTO channel_deliveries (
          delivery_id, channel, instance_id, target_id, source_kind, correlation_id,
          message_type, payload_fingerprint, state, part_count, next_part_index,
          payload_pruned, revision, created_at, updated_at
        ) VALUES (
          '%s', 'telegram', '%s', '10001', 'TURN_TERMINAL', 'turn-1',
          'TURN_COMPLETED', '%s', 'PENDING', 1, 0, 0, 0,
          '2026-07-16T00:00:00Z', '2026-07-16T00:00:00Z'
        )
        """
            .formatted(id, "a".repeat(64), "b".repeat(64)));
  }

  private static void insertPendingPart(java.sql.Statement statement, String deliveryId)
      throws Exception {
    statement.executeUpdate(
        """
        INSERT INTO channel_delivery_parts (
          delivery_id, part_index, payload_text, payload_fingerprint, state,
          attempt_count, updated_at
        ) VALUES (
          '%s', 0, 'payload', '%s', 'PENDING', 0, '2026-07-16T00:00:00Z'
        )
        """
            .formatted(deliveryId, "c".repeat(64)));
  }
}
