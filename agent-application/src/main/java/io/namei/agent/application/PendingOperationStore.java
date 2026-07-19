package io.namei.agent.application;

import io.namei.agent.kernel.tool.ToolResult;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Isolated durable store for a non-executing pending operation and its associated approval record.
 *
 * <p>Creating a record must atomically persist the Inbox entry and encrypted capsule. This port
 * does not authorize resumption, Tool invocation, or a Ledger transition.
 */
public interface PendingOperationStore {
  PendingOperation create(
      PendingOperation operation, ApprovalInboxEntry approval, PendingOperationCapsule capsule);

  Optional<PendingOperation> find(PendingOperationReference reference);

  /**
   * Returns a plaintext capsule only after the adapter has reconstructed the complete Operation,
   * authenticated the encrypted payload, and verified the complete binding.
   *
   * <p>This is not an execution right and must never expose ciphertext, nonce, key material, or raw
   * persisted parameter columns. A future capability must still obtain its own Reservation before
   * invocation.
   */
  default Optional<PendingOperationCapsule> loadVerifiedCapsule(
      PendingOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    throw new UnsupportedOperationException("Pending Operation Store 不支持已认证 Capsule 读取");
  }

  /**
   * Atomically consumes an already-approved Inbox record, advances its operation to {@code
   * CONSUMING}, and creates the only durable side-effect Reservation.
   *
   * <p>The returned value deliberately contains no Tool arguments and never invokes a Tool. A
   * replay is explicitly not an execution right.
   */
  PendingOperationReservation reserveApproved(
      PendingOperationReference reference, Instant observedAt);

  PendingOperationLedgerEntry markRunning(PendingOperationReference reference, Instant observedAt);

  PendingOperationLedgerEntry markSucceeded(
      PendingOperationReference reference, ToolResult safeResult, Instant observedAt);

  PendingOperationLedgerEntry markFailedBeforeStart(
      PendingOperationReference reference, ToolResult safeResult, Instant observedAt);

  PendingOperationLedgerEntry markUnknown(
      PendingOperationReference reference, String errorCode, Instant observedAt);

  PendingOperation markCommitUnreported(PendingOperationReference reference, Instant observedAt);

  Optional<PendingOperationLedgerEntry> findLedger(PendingOperationReference reference);
}
