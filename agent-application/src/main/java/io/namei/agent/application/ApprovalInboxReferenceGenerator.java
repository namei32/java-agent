package io.namei.agent.application;

@FunctionalInterface
public interface ApprovalInboxReferenceGenerator {
  ApprovalInboxReference next();
}
