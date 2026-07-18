package io.namei.agent.application;

/** Result of the one-time, non-executing handoff from an approved operation to its ledger slot. */
public enum PendingOperationReservationStatus {
  RESERVED,
  ALREADY_RESERVED,
  PENDING_APPROVAL,
  DENIED,
  EXPIRED,
  CANCELLED,
  STALE_SESSION,
  NOT_RESERVABLE,
  NOT_FOUND
}
