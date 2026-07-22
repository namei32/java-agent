package io.namei.agent.application;

public interface PendingOperationCapsuleCipher {
  EncryptedPendingOperationCapsule encrypt(
      PendingOperation operation, PendingOperationCapsule capsule);

  PendingOperationCapsule decrypt(
      PendingOperation operation, EncryptedPendingOperationCapsule encrypted);

  /**
   * 仅在认证非敏感持久绑定后解密。调用方必须重建完整的 {@link PendingOperation}。 使用前必须通过 {@link
   * PendingOperationCapsule#matches} 完成匹配校验。
   */
  PendingOperationCapsule decryptBound(
      PendingOperationCapsuleBinding binding, EncryptedPendingOperationCapsule encrypted);
}
