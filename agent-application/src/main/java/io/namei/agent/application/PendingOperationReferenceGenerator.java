package io.namei.agent.application;

@FunctionalInterface
public interface PendingOperationReferenceGenerator {
  PendingOperationReference next();
}
