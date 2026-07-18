package io.namei.agent.application;

import java.time.Instant;
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
   * Atomically consumes an already-approved Inbox record, advances its operation to {@code
   * CONSUMING}, and creates the only durable side-effect Reservation.
   *
   * <p>The returned value deliberately contains no Tool arguments and never invokes a Tool. A
   * replay is explicitly not an execution right.
   */
  PendingOperationReservation reserveApproved(
      PendingOperationReference reference, Instant observedAt);
}
