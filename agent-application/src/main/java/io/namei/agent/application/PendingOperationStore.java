package io.namei.agent.application;

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
}
