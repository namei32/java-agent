package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.ApprovalFingerprint;
import io.namei.agent.application.ApprovalInboxDecision;
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
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
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

class PendingOperationLedgerTest {
  private static final Instant ISSUED = Instant.parse("2026-07-19T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void recordsTheOnlyAllowedRunningAndSuccessTransitionsWithoutExposingTheCapsule() {
    JdbcPendingOperationStore store = reservedStore();

    assertThat(store.markRunning(operation().reference(), ISSUED.plusSeconds(3)).state())
        .isEqualTo(SideEffectExecutionState.RUNNING);
    assertThat(
            store
                .markSucceeded(
                    operation().reference(), ToolResult.success("已安全完成。"), ISSUED.plusSeconds(4))
                .state())
        .isEqualTo(SideEffectExecutionState.SUCCEEDED);

    assertThat(store.find(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.SUCCEEDED));
    assertThat(store.findLedger(operation().reference()))
        .hasValueSatisfying(
            value -> {
              assertThat(value.state()).isEqualTo(SideEffectExecutionState.SUCCEEDED);
              assertThat(value.safeResult()).contains(ToolResult.success("已安全完成。"));
              assertThat(value.toString()).doesNotContain("已安全完成。").contains("<redacted>");
            });
  }

  @Test
  void cannotMarkSuccessBeforeTheReservationRecordsRunning() {
    JdbcPendingOperationStore store = reservedStore();

    assertThatThrownBy(
            () ->
                store.markSucceeded(
                    operation().reference(), ToolResult.success("不应完成。"), ISSUED.plusSeconds(3)))
        .isInstanceOf(PendingOperationStoreException.class);
    assertThat(store.find(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.CONSUMING));
    assertThat(store.findLedger(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(SideEffectExecutionState.RESERVED));
  }

  @Test
  void recordsUnknownAsIrreversibleAndNeverFabricatesASafeResult() {
    JdbcPendingOperationStore store = reservedStore();
    store.markRunning(operation().reference(), ISSUED.plusSeconds(3));

    assertThat(
            store
                .markUnknown(operation().reference(), "INVOKER_UNCERTAIN", ISSUED.plusSeconds(4))
                .state())
        .isEqualTo(SideEffectExecutionState.UNKNOWN);
    assertThat(store.find(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.UNKNOWN));
    assertThat(store.findLedger(operation().reference()))
        .hasValueSatisfying(
            value -> {
              assertThat(value.safeResult()).isEmpty();
              assertThat(value.errorCode()).isEqualTo("INVOKER_UNCERTAIN");
            });
  }

  @Test
  void recordsFailureBeforeStartWithoutClaimingASideEffectRan() {
    JdbcPendingOperationStore store = reservedStore();

    assertThat(
            store
                .markFailedBeforeStart(
                    operation().reference(), ToolResult.cancelled(), ISSUED.plusSeconds(3))
                .state())
        .isEqualTo(SideEffectExecutionState.FAILED);
    assertThat(store.find(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.FAILED));
  }

  @Test
  void marksConversationCommitFailureOnlyAfterADurablyKnownSuccess() {
    JdbcPendingOperationStore store = reservedStore();
    store.markRunning(operation().reference(), ISSUED.plusSeconds(3));
    store.markSucceeded(operation().reference(), ToolResult.success("已完成。"), ISSUED.plusSeconds(4));

    assertThat(store.markCommitUnreported(operation().reference(), ISSUED.plusSeconds(5)).state())
        .isEqualTo(PendingOperationState.COMMIT_UNREPORTED);
    assertThat(store.findLedger(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(SideEffectExecutionState.SUCCEEDED));
  }

  @Test
  @Tag("failure")
  void rollsBackTheRunningTransitionWhenTheOperationStateUpdateFails() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = reserve(store(schema), schema);
    try (Connection connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TRIGGER fail_operation_state BEFORE UPDATE OF state ON pending_operations
          BEGIN SELECT RAISE(ABORT, 'operation-state-failure'); END
          """);
    }

    assertThatThrownBy(() -> store.markRunning(operation().reference(), ISSUED.plusSeconds(3)))
        .isInstanceOf(PendingOperationStoreException.class);
    assertThat(store.findLedger(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(SideEffectExecutionState.RESERVED));
  }

  @Test
  @Tag("failure")
  void failsClosedWhenAPersistedSafeResultIsMalformed() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = reserve(store(schema), schema);
    store.markRunning(operation().reference(), ISSUED.plusSeconds(3));
    store.markSucceeded(operation().reference(), ToolResult.success("已完成。"), ISSUED.plusSeconds(4));
    try (Connection connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE side_effect_reservations SET safe_result = 'SUCCESS:_w' WHERE operation_ref = ?")) {
      statement.setString(1, operation().reference().value());
      assertThat(statement.executeUpdate()).isOne();
    }

    assertThatThrownBy(() -> store.findLedger(operation().reference()))
        .isInstanceOf(PendingOperationStoreException.class)
        .hasMessageNotContaining("已完成。");
  }

  @Test
  @Tag("compat")
  void executesEveryVersionedOperationLedgerFixtureCase() throws Exception {
    JsonNode fixture =
        JSON.readTree(goldenRoot().resolve("tools/pending-operation-v1.json").toFile());
    assertThat(fixture.path("cases").size()).isEqualTo(41);
    for (JsonNode testCase : fixture.path("cases")) {
      String id = testCase.path("id").asText();
      if (id.startsWith("operation-ledger-")) {
        verifyFixture(id);
      }
    }
  }

  private void verifyFixture(String id) {
    JdbcPendingOperationStore store = reservedStore(id);
    switch (id) {
      case "operation-ledger-runs-before-success" -> {
        store.markRunning(operation().reference(), ISSUED.plusSeconds(3));
        assertThat(
                store
                    .markSucceeded(
                        operation().reference(), ToolResult.success("已完成。"), ISSUED.plusSeconds(4))
                    .state())
            .isEqualTo(SideEffectExecutionState.SUCCEEDED);
      }
      case "operation-ledger-rejects-direct-success" ->
          assertThatThrownBy(
                  () ->
                      store.markSucceeded(
                          operation().reference(),
                          ToolResult.success("不应完成。"),
                          ISSUED.plusSeconds(3)))
              .isInstanceOf(PendingOperationStoreException.class);
      case "operation-ledger-unknown-is-terminal" -> {
        store.markRunning(operation().reference(), ISSUED.plusSeconds(3));
        assertThat(
                store
                    .markUnknown(
                        operation().reference(), "INVOKER_UNCERTAIN", ISSUED.plusSeconds(4))
                    .state())
            .isEqualTo(SideEffectExecutionState.UNKNOWN);
      }
      case "operation-ledger-fails-before-start" ->
          assertThat(
                  store
                      .markFailedBeforeStart(
                          operation().reference(), ToolResult.cancelled(), ISSUED.plusSeconds(3))
                      .state())
              .isEqualTo(SideEffectExecutionState.FAILED);
      case "operation-ledger-commit-unreported-follows-success" -> {
        store.markRunning(operation().reference(), ISSUED.plusSeconds(3));
        store.markSucceeded(
            operation().reference(), ToolResult.success("已完成。"), ISSUED.plusSeconds(4));
        assertThat(
                store.markCommitUnreported(operation().reference(), ISSUED.plusSeconds(5)).state())
            .isEqualTo(PendingOperationState.COMMIT_UNREPORTED);
      }
      default -> throw new AssertionError("未知 Pending Operation Ledger Fixture Case: " + id);
    }
  }

  private JdbcPendingOperationStore reservedStore() {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    return reserve(store(schema), schema);
  }

  private JdbcPendingOperationStore reservedStore(String fixtureId) {
    ApprovalInboxSchemaInitializer schema =
        new ApprovalInboxSchemaInitializer(
            tempDir.resolve(fixtureId).resolve("approval-inbox.db"), 5_000);
    schema.initialize();
    return reserve(store(schema), schema);
  }

  private static JdbcPendingOperationStore reserve(
      JdbcPendingOperationStore store, ApprovalInboxSchemaInitializer schema) {
    PendingOperation operation = operation();
    store.create(operation, inbox(operation), capsule(operation));
    new JdbcApprovalInbox(schema)
        .resolve(
            inbox(operation).reference(),
            ApprovalInboxDecision.APPROVED,
            "local-operator",
            ISSUED.plusSeconds(1));
    assertThat(store.reserveApproved(operation.reference(), ISSUED.plusSeconds(2)).acquired())
        .isTrue();
    return store;
  }

  private ApprovalInboxSchemaInitializer schema() {
    return new ApprovalInboxSchemaInitializer(tempDir.resolve("approval-inbox.db"), 5_000);
  }

  private static JdbcPendingOperationStore store(ApprovalInboxSchemaInitializer schema) {
    return new JdbcPendingOperationStore(
        schema, new AesGcmPendingOperationCapsuleCipher(provider()));
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

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }
}
