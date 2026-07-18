package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.EncryptedPendingOperationCapsule;
import io.namei.agent.application.PendingOperation;
import io.namei.agent.application.PendingOperationCapsule;
import io.namei.agent.application.PendingOperationCapsuleException;
import io.namei.agent.application.PendingOperationKey;
import io.namei.agent.application.PendingOperationKeyProvider;
import io.namei.agent.application.PendingOperationReference;
import io.namei.agent.application.SecurePendingOperationReferenceGenerator;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class AesGcmPendingOperationCapsuleCipherTest {
  private static final Instant ISSUED = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void roundTripsOnlyWhenTheExactOperationAadAndKnownKeyMatch() {
    PendingOperation operation = operation();
    PendingOperationCapsule capsule =
        PendingOperationCapsule.forOperation(
            operation, "session-1", "{\"value\":1}", "boundary-v1");
    PendingOperationKey key = key("key-v1", (byte) 1);
    AesGcmPendingOperationCapsuleCipher cipher =
        new AesGcmPendingOperationCapsuleCipher(provider(key));

    EncryptedPendingOperationCapsule encrypted = cipher.encrypt(operation, capsule);

    assertThat(encrypted.nonce()).hasSize(12);
    assertThat(encrypted.ciphertext())
        .hasSizeGreaterThan(
            capsule
                .canonicalArgumentsJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                .length);
    assertThat(cipher.decrypt(operation, encrypted)).isEqualTo(capsule);
  }

  @Test
  @Tag("failure")
  void failsClosedForCiphertextTamperingReferenceReplacementAndUnknownKey() {
    PendingOperation operation = operation();
    PendingOperationCapsule capsule =
        PendingOperationCapsule.forOperation(
            operation, "session-1", "{\"value\":1}", "boundary-v1");
    PendingOperationKey key = key("key-v1", (byte) 1);
    AesGcmPendingOperationCapsuleCipher cipher =
        new AesGcmPendingOperationCapsuleCipher(provider(key));
    EncryptedPendingOperationCapsule encrypted = cipher.encrypt(operation, capsule);
    byte[] tampered = encrypted.ciphertext();
    tampered[0] ^= 1;

    assertThatThrownBy(
            () ->
                cipher.decrypt(
                    operation,
                    new EncryptedPendingOperationCapsule(
                        encrypted.schemaVersion(), encrypted.keyId(), encrypted.nonce(), tampered)))
        .isInstanceOf(PendingOperationCapsuleException.class);

    PendingOperation replacement =
        PendingOperation.pending(
            new SecurePendingOperationReferenceGenerator().next(), operation.approval(), 2, ISSUED);
    assertThatThrownBy(() -> cipher.decrypt(replacement, encrypted))
        .isInstanceOf(PendingOperationCapsuleException.class);

    assertThatThrownBy(
            () ->
                new AesGcmPendingOperationCapsuleCipher(provider(key("other-key", (byte) 2)))
                    .decrypt(operation, encrypted))
        .isInstanceOf(PendingOperationCapsuleException.class);
  }

  private static PendingOperation operation() {
    String arguments = "{\"value\":1}";
    ApprovalRequest request =
        new ApprovalRequest(
            "approval-id",
            io.namei.agent.application.ApprovalFingerprint.sessionBinding("session-1"),
            "turn-id",
            "call-id",
            "safe_write",
            "v1",
            ToolRisk.WRITE,
            io.namei.agent.application.ApprovalFingerprint.argumentsHashJson(arguments),
            "idempotency-key",
            "安全摘要",
            ISSUED,
            ISSUED.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64));
    return PendingOperation.pending(
        PendingOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA"), request, 2, ISSUED);
  }

  private static PendingOperationKey key(String keyId, byte value) {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, value);
    return new PendingOperationKey(keyId, new SecretKeySpec(bytes, "AES"));
  }

  private static PendingOperationKeyProvider provider(PendingOperationKey key) {
    return new PendingOperationKeyProvider() {
      @Override
      public PendingOperationKey current() {
        return key;
      }

      @Override
      public Optional<PendingOperationKey> findByKeyId(String keyId) {
        return key.keyId().equals(keyId) ? Optional.of(key) : Optional.empty();
      }
    };
  }
}
