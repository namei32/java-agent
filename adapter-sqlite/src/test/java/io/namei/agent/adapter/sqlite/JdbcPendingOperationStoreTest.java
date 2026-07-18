package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.ApprovalFingerprint;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.application.PendingOperation;
import io.namei.agent.application.PendingOperationCapsule;
import io.namei.agent.application.PendingOperationKey;
import io.namei.agent.application.PendingOperationKeyProvider;
import io.namei.agent.application.PendingOperationReference;
import io.namei.agent.application.PendingOperationStoreException;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcPendingOperationStoreTest {
  private static final Instant ISSUED = Instant.parse("2026-07-19T00:00:00Z");

  @TempDir Path tempDir;

  @Test
  void persistsTheApprovalAndAuthenticatedCapsuleInOneIsolatedTransaction() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = store(schema);
    PendingOperation operation = operation();
    ApprovalInboxEntry inbox = inbox(operation);
    PendingOperationCapsule capsule = capsule(operation);

    assertThat(store.create(operation, inbox, capsule)).isEqualTo(operation);
    assertThat(store.find(operation.reference())).contains(operation);

    try (Connection connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                "SELECT state, capsule_key_id, capsule_nonce, capsule_ciphertext "
                    + "FROM pending_operations WHERE operation_ref = ?")) {
      statement.setString(1, operation.reference().value());
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("state")).isEqualTo("PENDING_APPROVAL");
        assertThat(rows.getString("capsule_key_id")).isEqualTo("key-v1");
        assertThat(rows.getBytes("capsule_nonce")).hasSize(12);
        assertThat(rows.getBytes("capsule_ciphertext")).hasSizeGreaterThan(16);
        assertThat(rows.next()).isFalse();
      }
    }
    try (Connection connection = schema.openConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM approval_inbox_entries")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isOne();
    }
  }

  @Test
  void migratesAV1ApprovalInboxToTheV2OperationStoreWithoutDroppingTheInboxTable()
      throws Exception {
    Path database = tempDir.resolve("approval-inbox.db");
    createV1(database);

    ApprovalInboxSchemaInitializer schema = new ApprovalInboxSchemaInitializer(database, 5_000);
    schema.initialize();

    try (Connection connection = schema.openConnection();
        var versionStatement = connection.createStatement();
        var tableStatement = connection.createStatement();
        var version = versionStatement.executeQuery("SELECT version FROM approval_inbox_schema");
        var tables =
            tableStatement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' "
                    + "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
      assertThat(version.next()).isTrue();
      assertThat(version.getInt(1)).isEqualTo(2);
      var names = new java.util.ArrayList<String>();
      while (tables.next()) {
        names.add(tables.getString(1));
      }
      assertThat(names)
          .containsExactly(
              "approval_inbox_entries",
              "approval_inbox_schema",
              "pending_operations",
              "side_effect_reservations");
    }
    assertThat(new JdbcApprovalInbox(schema).list(ISSUED, 64))
        .singleElement()
        .satisfies(entry -> assertThat(entry.state()).isEqualTo(ApprovalState.PENDING));
  }

  @Test
  @Tag("failure")
  void refusesToLoadAnOperationWhenItsPersistedCapsuleIsTampered() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = store(schema);
    PendingOperation operation = operation();
    store.create(operation, inbox(operation), capsule(operation));

    try (Connection connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE pending_operations SET capsule_ciphertext = zeroblob(length(capsule_ciphertext)) "
                    + "WHERE operation_ref = ?")) {
      statement.setString(1, operation.reference().value());
      assertThat(statement.executeUpdate()).isOne();
    }

    assertThatThrownBy(() -> store.find(operation.reference()))
        .isInstanceOf(PendingOperationStoreException.class)
        .hasMessageNotContaining("session-1")
        .hasMessageNotContaining("turn-id")
        .hasMessageNotContaining("call-id")
        .hasMessageNotContaining("idempotency-key");
  }

  @Test
  @Tag("failure")
  void rollsBackTheInboxWriteWhenTheOperationInsertFails() {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = store(schema);
    PendingOperation original = operation();
    store.create(original, inbox(original), capsule(original));
    PendingOperation duplicateReference = operation("approval-id-2");

    assertThatThrownBy(
            () ->
                store.create(
                    duplicateReference, inbox(duplicateReference), capsule(duplicateReference)))
        .isInstanceOf(PendingOperationStoreException.class);

    assertThat(new JdbcApprovalInbox(schema).list(ISSUED, 64)).hasSize(1);
  }

  private ApprovalInboxSchemaInitializer schema() {
    return new ApprovalInboxSchemaInitializer(tempDir.resolve("approval-inbox.db"), 5_000);
  }

  private static JdbcPendingOperationStore store(ApprovalInboxSchemaInitializer schema) {
    return new JdbcPendingOperationStore(
        schema, new AesGcmPendingOperationCapsuleCipher(provider()));
  }

  private static PendingOperation operation() {
    return operation("approval-id");
  }

  private static PendingOperation operation(String approvalId) {
    String arguments = "{\"value\":1,\"optional\":null}";
    ApprovalRequest request =
        new ApprovalRequest(
            approvalId,
            ApprovalFingerprint.sessionBinding("session-1"),
            "turn-id",
            "call-id",
            "safe_write",
            "v1",
            ToolRisk.WRITE,
            ApprovalFingerprint.argumentsHashJson(arguments),
            "idempotency-key",
            "安全摘要",
            ISSUED,
            ISSUED.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64));
    return PendingOperation.pending(
        PendingOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"), request, 2, ISSUED);
  }

  private static ApprovalInboxEntry inbox(PendingOperation operation) {
    String reference =
        operation.approval().approvalId().equals("approval-id")
            ? "AQEBAQEBAQEBAQEBAQEBAQ"
            : "AgICAgICAgICAgICAgICAg";
    return ApprovalInboxEntry.pending(ApprovalInboxReference.of(reference), operation.approval());
  }

  private static PendingOperationCapsule capsule(PendingOperation operation) {
    return PendingOperationCapsule.forOperation(
        operation, "session-1", "{\"value\":1,\"optional\":null}", "boundary-v1");
  }

  private static PendingOperationKeyProvider provider() {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) 1);
    PendingOperationKey key = new PendingOperationKey("key-v1", new SecretKeySpec(bytes, "AES"));
    return new PendingOperationKeyProvider() {
      @Override
      public PendingOperationKey current() {
        return key;
      }

      @Override
      public Optional<PendingOperationKey> findByKeyId(String keyId) {
        return key.keyId().equals(keyId) ? Optional.of(key) : Optional.empty();
      }
    };
  }

  private static void createV1(Path database) throws Exception {
    try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE approval_inbox_schema (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
              + "version INTEGER NOT NULL CHECK (version = 1))");
      statement.execute("INSERT INTO approval_inbox_schema(singleton, version) VALUES (1, 1)");
      statement.execute(
          "CREATE TABLE approval_inbox_entries (approval_id TEXT PRIMARY KEY NOT NULL, "
              + "approval_ref TEXT UNIQUE NOT NULL, session_binding TEXT NOT NULL, turn_id TEXT NOT NULL, "
              + "call_id TEXT NOT NULL, tool_name TEXT NOT NULL, tool_version TEXT NOT NULL, risk TEXT NOT NULL, "
              + "arguments_hash TEXT NOT NULL, idempotency_key TEXT NOT NULL, summary TEXT NOT NULL, "
              + "issued_at TEXT NOT NULL, issued_epoch_second INTEGER NOT NULL, issued_nano INTEGER NOT NULL, "
              + "expires_at TEXT NOT NULL, expires_epoch_second INTEGER NOT NULL, expires_nano INTEGER NOT NULL, "
              + "fingerprint_version TEXT NOT NULL, fingerprint TEXT NOT NULL, state TEXT NOT NULL, "
              + "decided_at TEXT, actor_reference TEXT NOT NULL)");
      statement.execute(
          "INSERT INTO approval_inbox_entries (approval_id, approval_ref, session_binding, turn_id, "
              + "call_id, tool_name, tool_version, risk, arguments_hash, idempotency_key, summary, "
              + "issued_at, issued_epoch_second, issued_nano, expires_at, expires_epoch_second, "
              + "expires_nano, fingerprint_version, fingerprint, state, decided_at, actor_reference) "
              + "VALUES ('legacy-approval', 'AwMDAwMDAwMDAwMDAwMDAw', 'a', 'b', 'c', 'safe_write', "
              + "'v1', 'WRITE', '"
              + "d".repeat(64)
              + "', 'legacy-idempotency', 'legacy summary', '2026-07-19T00:00:00Z', 1784419200, 0, "
              + "'2026-07-19T00:05:00Z', 1784419500, 0, 'approval-fingerprint-v1', '"
              + "e".repeat(64)
              + "', 'PENDING', NULL, '')");
    }
  }
}
