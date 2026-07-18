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
import io.namei.agent.application.PendingOperationState;
import io.namei.agent.application.PendingOperationStoreException;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class PendingOperationStoreGoldenTest {
  private static final Instant ISSUED = Instant.parse("2026-07-19T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void executesEveryVersionedOperationStoreFixtureCaseAgainstTheProductionStore() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/pending-operation-v1.json").toFile());
    assertThat(fixture.path("cases").size()).isEqualTo(21);
    for (JsonNode testCase : fixture.path("cases")) {
      String id = testCase.path("id").asText();
      if (id.startsWith("operation-store-")) {
        verify(id);
      }
    }
  }

  private void verify(String id) throws Exception {
    Path database = tempDir.resolve(id).resolve("approval-inbox.db");
    if (id.equals("operation-store-migrates-v1")) {
      createV1(database);
      ApprovalInboxSchemaInitializer schema = new ApprovalInboxSchemaInitializer(database, 5_000);
      schema.initialize();
      try (Connection connection = schema.openConnection();
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT version FROM approval_inbox_schema")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(2);
      }
      return;
    }

    ApprovalInboxSchemaInitializer schema = new ApprovalInboxSchemaInitializer(database, 5_000);
    schema.initialize();
    JdbcPendingOperationStore store =
        new JdbcPendingOperationStore(schema, new AesGcmPendingOperationCapsuleCipher(provider()));
    PendingOperation operation = operation();
    store.create(operation, inbox(operation), capsule(operation));
    switch (id) {
      case "operation-store-persists-atomically" ->
          assertThat(store.find(operation.reference()))
              .hasValueSatisfying(
                  value ->
                      assertThat(value.state()).isEqualTo(PendingOperationState.PENDING_APPROVAL));
      case "operation-store-rejects-capsule-tamper" -> {
        try (Connection connection = schema.openConnection();
            var statement =
                connection.prepareStatement(
                    "UPDATE pending_operations SET capsule_ciphertext = zeroblob(length(capsule_ciphertext)) "
                        + "WHERE operation_ref = ?")) {
          statement.setString(1, operation.reference().value());
          assertThat(statement.executeUpdate()).isOne();
        }
        assertThatThrownBy(() -> store.find(operation.reference()))
            .isInstanceOf(PendingOperationStoreException.class);
      }
      default -> throw new AssertionError("未知 Pending Operation Store Fixture Case: " + id);
    }
  }

  private static PendingOperation operation() {
    String arguments = "{\"value\":1}";
    ApprovalRequest request =
        new ApprovalRequest(
            "approval-id",
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
    return ApprovalInboxEntry.pending(
        ApprovalInboxReference.of("AQEBAQEBAQEBAQEBAQEBAQ"), operation.approval());
  }

  private static PendingOperationCapsule capsule(PendingOperation operation) {
    return PendingOperationCapsule.forOperation(
        operation, "session-1", "{\"value\":1}", "boundary-v1");
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
    Files.createDirectories(database.getParent());
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
    }
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
