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
