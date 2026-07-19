package io.namei.agent.application;

@FunctionalInterface
public interface MemoryForgetRecovery {
  MemoryForgetRecoveryCoordinator.Outcome resume(PendingOperationReference reference);
}
