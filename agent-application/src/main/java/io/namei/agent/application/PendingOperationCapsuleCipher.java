package io.namei.agent.application;

public interface PendingOperationCapsuleCipher {
  EncryptedPendingOperationCapsule encrypt(
      PendingOperation operation, PendingOperationCapsule capsule);

  PendingOperationCapsule decrypt(
      PendingOperation operation, EncryptedPendingOperationCapsule encrypted);

  /**
   * Decrypts only after authenticating the non-sensitive persisted binding. Callers must rebuild
   * the complete {@link PendingOperation} and invoke {@link PendingOperationCapsule#matches} before
   * use.
   */
  PendingOperationCapsule decryptBound(
      PendingOperationCapsuleBinding binding, EncryptedPendingOperationCapsule encrypted);
}
