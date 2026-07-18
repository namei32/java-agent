package io.namei.agent.adapter.sqlite;

import io.namei.agent.application.ApprovalFingerprint;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.EncryptedPendingOperationCapsule;
import io.namei.agent.application.PendingOperation;
import io.namei.agent.application.PendingOperationCapsule;
import io.namei.agent.application.PendingOperationCapsuleBinding;
import io.namei.agent.application.PendingOperationCapsuleCipher;
import io.namei.agent.application.PendingOperationCapsuleException;
import io.namei.agent.application.PendingOperationReference;
import io.namei.agent.application.PendingOperationReservation;
import io.namei.agent.application.PendingOperationReservationStatus;
import io.namei.agent.application.PendingOperationState;
import io.namei.agent.application.PendingOperationStore;
import io.namei.agent.application.PendingOperationStoreException;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * V2 isolated SQLite store. It persists an Inbox record and an authenticated capsule atomically,
 * but deliberately does not consume approval, reserve a Ledger entry, or invoke a Tool.
 */
public final class JdbcPendingOperationStore implements PendingOperationStore {
  private final ApprovalInboxSchemaInitializer schema;
  private final PendingOperationCapsuleCipher cipher;

  public JdbcPendingOperationStore(
      ApprovalInboxSchemaInitializer schema, PendingOperationCapsuleCipher cipher) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
  }

  @Override
  public PendingOperation create(
      PendingOperation operation, ApprovalInboxEntry approval, PendingOperationCapsule capsule) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(approval, "approval");
    Objects.requireNonNull(capsule, "capsule");
    if (operation.state() != PendingOperationState.PENDING_APPROVAL
        || approval.state() != ApprovalState.PENDING
        || !approval.request().equals(operation.approval())
        || !capsule.matches(operation)) {
      throw new IllegalArgumentException("待审批操作创建输入不一致");
    }
    try {
      EncryptedPendingOperationCapsule encrypted = cipher.encrypt(operation, capsule);
      try (var connection = schema.openConnection()) {
        return ApprovalInboxSchemaInitializer.transaction(
            connection,
            () -> {
              if (JdbcApprovalInbox.entryCount(connection) >= JdbcApprovalInbox.MAX_ENTRIES) {
                throw ApprovalInboxRepositoryException.capacityExceeded();
              }
              JdbcApprovalInbox.insert(connection, approval);
              insertOperation(connection, operation, approval, encrypted);
              return operation;
            });
      }
    } catch (PendingOperationStoreException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw new PendingOperationStoreException(exception);
    }
  }

  @Override
  public Optional<PendingOperation> find(PendingOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    try (var connection = schema.openConnection()) {
      return find(connection, reference);
    } catch (PendingOperationStoreException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw new PendingOperationStoreException(exception);
    }
  }

  @Override
  public PendingOperationReservation reserveApproved(
      PendingOperationReference reference, Instant observedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    try (var connection = schema.openConnection()) {
      return ApprovalInboxSchemaInitializer.immediateTransaction(
          connection, () -> reserveApproved(connection, reference, observedAt));
    } catch (PendingOperationStoreException exception) {
      throw exception;
    } catch (SQLException | RuntimeException exception) {
      throw new PendingOperationStoreException(exception);
    }
  }

  private PendingOperationReservation reserveApproved(
      Connection connection, PendingOperationReference reference, Instant observedAt)
      throws SQLException {
    StoredOperationState stored = findState(connection, reference);
    if (stored == null) {
      return PendingOperationReservation.notFound();
    }
    if (isExpired(stored, observedAt)
        && (stored.operationState() == PendingOperationState.PENDING_APPROVAL
            || stored.operationState() == PendingOperationState.APPROVED_PENDING_RESUME)) {
      updateOperationState(
          connection,
          reference,
          stored.operationState(),
          PendingOperationState.EXPIRED,
          observedAt);
      return result(PendingOperationReservationStatus.EXPIRED, connection, reference);
    }
    return switch (stored.operationState()) {
      case PENDING_APPROVAL, APPROVED_PENDING_RESUME ->
          reservePending(connection, stored, reference, observedAt);
      case CONSUMING -> {
        if (!hasReservation(connection, stored)) {
          throw new PendingOperationStoreException();
        }
        yield result(PendingOperationReservationStatus.ALREADY_RESERVED, connection, reference);
      }
      case DENIED -> result(PendingOperationReservationStatus.DENIED, connection, reference);
      case EXPIRED -> result(PendingOperationReservationStatus.EXPIRED, connection, reference);
      case CANCELLED -> result(PendingOperationReservationStatus.CANCELLED, connection, reference);
      case STALE_SESSION ->
          result(PendingOperationReservationStatus.STALE_SESSION, connection, reference);
      case SUCCEEDED, FAILED, UNKNOWN, COMMIT_UNREPORTED ->
          result(PendingOperationReservationStatus.NOT_RESERVABLE, connection, reference);
    };
  }

  private PendingOperationReservation reservePending(
      Connection connection,
      StoredOperationState stored,
      PendingOperationReference reference,
      Instant observedAt)
      throws SQLException {
    return switch (stored.approvalState()) {
      case PENDING ->
          result(PendingOperationReservationStatus.PENDING_APPROVAL, connection, reference);
      case DENIED -> {
        updateOperationState(
            connection,
            reference,
            stored.operationState(),
            PendingOperationState.DENIED,
            observedAt);
        yield result(PendingOperationReservationStatus.DENIED, connection, reference);
      }
      case CANCELLED -> {
        updateOperationState(
            connection,
            reference,
            stored.operationState(),
            PendingOperationState.CANCELLED,
            observedAt);
        yield result(PendingOperationReservationStatus.CANCELLED, connection, reference);
      }
      case EXPIRED -> {
        updateOperationState(
            connection,
            reference,
            stored.operationState(),
            PendingOperationState.EXPIRED,
            observedAt);
        yield result(PendingOperationReservationStatus.EXPIRED, connection, reference);
      }
      case APPROVED -> {
        consumeApproval(connection, stored);
        updateOperationState(
            connection,
            reference,
            stored.operationState(),
            PendingOperationState.CONSUMING,
            observedAt);
        insertReservation(connection, stored, observedAt);
        yield result(PendingOperationReservationStatus.RESERVED, connection, reference);
      }
      case CONSUMED -> throw new PendingOperationStoreException();
    };
  }

  private static StoredOperationState findState(
      Connection connection, PendingOperationReference reference) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT p.operation_ref, p.approval_id, p.state, a.state,
                   a.expires_epoch_second, a.expires_nano
            FROM pending_operations p
            JOIN approval_inbox_entries a ON a.approval_id = p.approval_id
            WHERE p.operation_ref = ?
            """)) {
      statement.setString(1, reference.value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        PendingOperationReference storedReference =
            PendingOperationReference.of(rows.getString("operation_ref"));
        if (!reference.equals(storedReference)) {
          throw new PendingOperationStoreException();
        }
        StoredOperationState stored =
            new StoredOperationState(
                storedReference,
                rows.getString("approval_id"),
                PendingOperationState.valueOf(rows.getString(3)),
                ApprovalState.valueOf(rows.getString(4)),
                rows.getLong(5),
                rows.getInt(6));
        if (rows.next()) {
          throw new PendingOperationStoreException();
        }
        return stored;
      }
    } catch (IllegalArgumentException exception) {
      throw new PendingOperationStoreException(exception);
    }
  }

  private static boolean isExpired(StoredOperationState stored, Instant observedAt) {
    return observedAt.getEpochSecond() > stored.expiresEpochSecond()
        || (observedAt.getEpochSecond() == stored.expiresEpochSecond()
            && observedAt.getNano() >= stored.expiresNano());
  }

  private static void consumeApproval(Connection connection, StoredOperationState stored)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "UPDATE approval_inbox_entries SET state = 'CONSUMED' "
                + "WHERE approval_id = ? AND state = 'APPROVED'")) {
      statement.setString(1, stored.approvalId());
      if (statement.executeUpdate() != 1) {
        throw new PendingOperationStoreException();
      }
    }
  }

  private static void updateOperationState(
      Connection connection,
      PendingOperationReference reference,
      PendingOperationState expected,
      PendingOperationState next,
      Instant changedAt)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "UPDATE pending_operations SET state = ?, state_changed_at = ? "
                + "WHERE operation_ref = ? AND state = ?")) {
      statement.setString(1, next.name());
      statement.setString(2, changedAt.toString());
      statement.setString(3, reference.value());
      statement.setString(4, expected.name());
      if (statement.executeUpdate() != 1) {
        throw new PendingOperationStoreException();
      }
    }
  }

  private static void insertReservation(
      Connection connection, StoredOperationState stored, Instant reservedAt) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO side_effect_reservations (
              operation_ref, approval_id, state, state_changed_at, safe_result, error_code
            ) VALUES (?, ?, 'RESERVED', ?, NULL, '')
            """)) {
      statement.setString(1, stored.reference().value());
      statement.setString(2, stored.approvalId());
      statement.setString(3, reservedAt.toString());
      if (statement.executeUpdate() != 1) {
        throw new PendingOperationStoreException();
      }
    }
  }

  private static boolean hasReservation(Connection connection, StoredOperationState stored)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM side_effect_reservations WHERE operation_ref = ? AND approval_id = ?")) {
      statement.setString(1, stored.reference().value());
      statement.setString(2, stored.approvalId());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new PendingOperationStoreException();
        }
        long count = rows.getLong(1);
        if (rows.wasNull() || count != 1 || rows.next()) {
          throw new PendingOperationStoreException();
        }
        return true;
      }
    }
  }

  private PendingOperationReservation result(
      PendingOperationReservationStatus status,
      Connection connection,
      PendingOperationReference reference)
      throws SQLException {
    return PendingOperationReservation.of(
        status, find(connection, reference).orElseThrow(PendingOperationStoreException::new));
  }

  private record StoredOperationState(
      PendingOperationReference reference,
      String approvalId,
      PendingOperationState operationState,
      ApprovalState approvalState,
      long expiresEpochSecond,
      int expiresNano) {
    private StoredOperationState {
      Objects.requireNonNull(reference, "reference");
      Objects.requireNonNull(approvalId, "approvalId");
      Objects.requireNonNull(operationState, "operationState");
      Objects.requireNonNull(approvalState, "approvalState");
      if (expiresNano < 0 || expiresNano > 999_999_999) {
        throw new IllegalArgumentException("审批到期纳秒无效");
      }
    }
  }

  private static void insertOperation(
      Connection connection,
      PendingOperation operation,
      ApprovalInboxEntry approval,
      EncryptedPendingOperationCapsule encrypted)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO pending_operations (
              operation_ref, approval_id, approval_ref, expected_next_sequence, state,
              state_changed_at, capsule_schema_version, capsule_key_id, capsule_nonce,
              capsule_ciphertext
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, operation.reference().value());
      statement.setString(2, operation.approval().approvalId());
      statement.setString(3, approval.reference().value());
      statement.setLong(4, operation.expectedNextSequence());
      statement.setString(5, operation.state().name());
      statement.setString(6, operation.stateChangedAt().toString());
      statement.setInt(7, encrypted.schemaVersion());
      statement.setString(8, encrypted.keyId());
      statement.setBytes(9, encrypted.nonce());
      statement.setBytes(10, encrypted.ciphertext());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("待审批操作写入行数不是 1");
      }
    }
  }

  private Optional<PendingOperation> find(
      Connection connection, PendingOperationReference reference) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT p.operation_ref, p.approval_ref AS operation_approval_ref,
                   p.expected_next_sequence, p.state, p.state_changed_at,
                   p.capsule_schema_version, p.capsule_key_id, p.capsule_nonce,
                   p.capsule_ciphertext, a.approval_id, a.approval_ref AS inbox_approval_ref,
                   a.tool_name, a.tool_version, a.risk, a.arguments_hash, a.idempotency_key,
                   a.summary, a.issued_at, a.expires_at, a.fingerprint_version, a.fingerprint
            FROM pending_operations p
            JOIN approval_inbox_entries a ON a.approval_id = p.approval_id
            WHERE p.operation_ref = ?
            """)) {
      statement.setString(1, reference.value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        PendingOperation operation = read(rows, reference);
        if (rows.next()) {
          throw new PendingOperationStoreException();
        }
        return Optional.of(operation);
      }
    }
  }

  private PendingOperation read(ResultSet rows, PendingOperationReference reference)
      throws SQLException {
    try {
      PendingOperationReference storedReference =
          PendingOperationReference.of(rows.getString("operation_ref"));
      if (!storedReference.equals(reference)) {
        throw new PendingOperationStoreException();
      }
      if (!rows.getString("operation_approval_ref").equals(rows.getString("inbox_approval_ref"))) {
        throw new PendingOperationStoreException();
      }
      String fingerprint = rows.getString("fingerprint");
      String toolVersion = rows.getString("tool_version");
      EncryptedPendingOperationCapsule encrypted =
          new EncryptedPendingOperationCapsule(
              rows.getInt("capsule_schema_version"),
              rows.getString("capsule_key_id"),
              rows.getBytes("capsule_nonce"),
              rows.getBytes("capsule_ciphertext"));
      PendingOperationCapsule capsule =
          cipher.decryptBound(
              new PendingOperationCapsuleBinding(storedReference, fingerprint, toolVersion),
              encrypted);
      ApprovalRequest approval = approval(rows, capsule, fingerprint, toolVersion);
      PendingOperation operation =
          new PendingOperation(
              storedReference,
              approval,
              rows.getLong("expected_next_sequence"),
              PendingOperationState.valueOf(rows.getString("state")),
              Instant.parse(rows.getString("state_changed_at")));
      if (!capsule.matches(operation)) {
        throw new PendingOperationStoreException();
      }
      return operation;
    } catch (PendingOperationCapsuleException | IllegalArgumentException exception) {
      throw new PendingOperationStoreException(exception);
    }
  }

  private static ApprovalRequest approval(
      ResultSet rows, PendingOperationCapsule capsule, String fingerprint, String toolVersion)
      throws SQLException {
    String argumentsHash = ApprovalFingerprint.argumentsHashJson(capsule.canonicalArgumentsJson());
    if (!rows.getString("approval_id").equals(capsule.approvalId())
        || !rows.getString("tool_name").equals(capsule.toolName())
        || !toolVersion.equals(capsule.toolVersion())
        || !rows.getString("risk").equals(capsule.risk())
        || !rows.getString("arguments_hash").equals(argumentsHash)
        || !rows.getString("idempotency_key").equals(capsule.idempotencyKey())
        || !fingerprint.equals(capsule.fingerprint())) {
      throw new PendingOperationStoreException();
    }
    return new ApprovalRequest(
        capsule.approvalId(),
        ApprovalFingerprint.sessionBinding(capsule.sessionId()),
        capsule.turnId(),
        capsule.callId(),
        capsule.toolName(),
        capsule.toolVersion(),
        ToolRisk.valueOf(capsule.risk()),
        argumentsHash,
        capsule.idempotencyKey(),
        rows.getString("summary"),
        Instant.parse(rows.getString("issued_at")),
        Instant.parse(rows.getString("expires_at")),
        rows.getString("fingerprint_version"),
        fingerprint);
  }
}
