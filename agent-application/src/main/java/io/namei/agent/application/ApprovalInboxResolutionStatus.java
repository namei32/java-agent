package io.namei.agent.application;

/** Approval Inbox 原子决议尝试的稳定结果。 */
public enum ApprovalInboxResolutionStatus {
  RESOLVED,
  NOT_FOUND,
  ALREADY_RESOLVED,
  EXPIRED
}
