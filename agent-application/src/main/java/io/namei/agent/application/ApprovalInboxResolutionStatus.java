package io.namei.agent.application;

/** Stable result of an atomic approval-inbox decision attempt. */
public enum ApprovalInboxResolutionStatus {
  RESOLVED,
  NOT_FOUND,
  ALREADY_RESOLVED,
  EXPIRED
}
