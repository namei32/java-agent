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
import io.namei.agent.application.PendingOperationReservationStatus;
import io.namei.agent.application.PendingOperationState;
import io.namei.agent.application.PendingOperationStoreException;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingOperationReservationTest {
  private static final Instant ISSUED = Instant.parse("2026-07-19T00:00:00Z");

  @TempDir Path tempDir;

  @Test
  void consumesAnApprovedInboxEntryAndCreatesExactlyOneReservationInTheSameTransaction()
      throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = create(store(schema));
    approve(schema);

    var reservation = store.reserveApproved(operation().reference(), ISSUED.plusSeconds(2));

    assertThat(reservation.status()).isEqualTo(PendingOperationReservationStatus.RESERVED);
    assertThat(reservation.acquired()).isTrue();
    assertThat(reservation.operation())
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.CONSUMING));
    assertThat(store.find(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.CONSUMING));
    assertDatabaseState(schema, "CONSUMED", "CONSUMING", 1);
  }

  @Test
  void leavesAPendingApprovalAndOperationUntouchedUntilAnOperatorApprovesIt() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = create(store(schema));

    var reservation = store.reserveApproved(operation().reference(), ISSUED.plusSeconds(1));

    assertThat(reservation.status()).isEqualTo(PendingOperationReservationStatus.PENDING_APPROVAL);
    assertThat(reservation.acquired()).isFalse();
    assertDatabaseState(schema, "PENDING", "PENDING_APPROVAL", 0);
  }

  @Test
  void exposesAPlaintextCapsuleOnlyAfterTheStoredOperationBindingAuthenticates() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = create(store(schema));

    assertThat(store.loadVerifiedCapsule(operation().reference())).contains(capsule(operation()));

    try (Connection connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE pending_operations SET capsule_ciphertext = zeroblob(length(capsule_ciphertext)) "
                    + "WHERE operation_ref = ?")) {
      statement.setString(1, operation().reference().value());
      assertThat(statement.executeUpdate()).isOne();
    }
    assertThatThrownBy(() -> store.loadVerifiedCapsule(operation().reference()))
        .isInstanceOf(PendingOperationStoreException.class);
  }

  @Test
  void expiryBeatsAnApprovedButNotYetConsumedOperation() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = create(store(schema));
    approve(schema);

    var reservation = store.reserveApproved(operation().reference(), ISSUED.plusSeconds(300));

    assertThat(reservation.status()).isEqualTo(PendingOperationReservationStatus.EXPIRED);
    assertThat(reservation.acquired()).isFalse();
    assertThat(store.find(operation().reference()))
        .hasValueSatisfying(
            value -> assertThat(value.state()).isEqualTo(PendingOperationState.EXPIRED));
    assertDatabaseState(schema, "APPROVED", "EXPIRED", 0);
  }

  @Test
  void aRepeatedReservationNeverCreatesAnotherLedgerEntryOrAnExecutionRight() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = create(store(schema));
    approve(schema);

    assertThat(store.reserveApproved(operation().reference(), ISSUED.plusSeconds(2)).acquired())
        .isTrue();
    var replay = store.reserveApproved(operation().reference(), ISSUED.plusSeconds(3));

    assertThat(replay.status()).isEqualTo(PendingOperationReservationStatus.ALREADY_RESERVED);
    assertThat(replay.acquired()).isFalse();
    assertDatabaseState(schema, "CONSUMED", "CONSUMING", 1);
  }

  @Test
  void concurrentReservationsProduceOneOwnerAndOneNonExecutionReplay() throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = create(store(schema));
    approve(schema);
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> {
                start.await();
                return store
                    .reserveApproved(operation().reference(), ISSUED.plusSeconds(2))
                    .status();
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return store
                    .reserveApproved(operation().reference(), ISSUED.plusSeconds(2))
                    .status();
              });

      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(
              PendingOperationReservationStatus.RESERVED,
              PendingOperationReservationStatus.ALREADY_RESERVED);
      assertDatabaseState(schema, "CONSUMED", "CONSUMING", 1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @Tag("failure")
  void rollsBackApprovalConsumptionAndOperationTransitionWhenReservationInsertFails()
      throws Exception {
    ApprovalInboxSchemaInitializer schema = schema();
    schema.initialize();
    JdbcPendingOperationStore store = create(store(schema));
    approve(schema);
    try (Connection connection = schema.openConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TRIGGER fail_reservation BEFORE INSERT ON side_effect_reservations
          BEGIN SELECT RAISE(ABORT, 'reservation-failure'); END
          """);
    }

    assertThatThrownBy(() -> store.reserveApproved(operation().reference(), ISSUED.plusSeconds(2)))
        .isInstanceOf(PendingOperationStoreException.class);

    assertDatabaseState(schema, "APPROVED", "PENDING_APPROVAL", 0);
  }

  private JdbcPendingOperationStore create(JdbcPendingOperationStore store) {
    PendingOperation operation = operation();
    store.create(operation, inbox(operation), capsule(operation));
    return store;
  }

  private void approve(ApprovalInboxSchemaInitializer schema) {
    assertThat(
            new JdbcApprovalInbox(schema)
                .resolve(
                    inbox(operation()).reference(),
                    ApprovalInboxDecision.APPROVED,
                    "local-operator",
                    ISSUED.plusSeconds(1))
                .entry())
        .hasValueSatisfying(value -> assertThat(value.state()).isEqualTo(ApprovalState.APPROVED));
  }

  private static void assertDatabaseState(
      ApprovalInboxSchemaInitializer schema,
      String approvalState,
      String operationState,
      int reservationCount)
      throws Exception {
    try (Connection connection = schema.openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT a.state, p.state,
                       (SELECT COUNT(*) FROM side_effect_reservations) AS reservation_count
                FROM approval_inbox_entries a
                JOIN pending_operations p ON p.approval_id = a.approval_id
                """)) {
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(approvalState);
        assertThat(rows.getString(2)).isEqualTo(operationState);
        assertThat(rows.getInt(3)).isEqualTo(reservationCount);
        assertThat(rows.next()).isFalse();
      }
    }
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
}
