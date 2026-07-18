package io.namei.agent.application;

public interface PendingOperationCapsuleCipher {
  EncryptedPendingOperationCapsule encrypt(
      PendingOperation operation, PendingOperationCapsule capsule);

  PendingOperationCapsule decrypt(
      PendingOperation operation, EncryptedPendingOperationCapsule encrypted);
}
