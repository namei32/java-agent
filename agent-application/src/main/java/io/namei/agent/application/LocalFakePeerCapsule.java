package io.namei.agent.application;

import io.namei.agent.kernel.proactive.LocalFakePeerCard;
import io.namei.agent.kernel.proactive.LocalFakePeerManifest;
import io.namei.agent.kernel.proactive.LocalFakePeerTaskKind;
import io.namei.agent.kernel.proactive.PeerTaskRef;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Authenticated P4 Capsule. Its static card has no endpoint, command, or task body. */
final class LocalFakePeerCapsule {
  static final int SCHEMA_VERSION = 1;

  private final PeerTaskRef reference;
  private final String approvalId;
  private final String fingerprint;
  private final String idempotencyKey;
  private final LocalFakePeerCard card;

  LocalFakePeerCapsule(
      PeerTaskRef reference,
      String approvalId,
      String fingerprint,
      String idempotencyKey,
      LocalFakePeerCard card) {
    this.reference = Objects.requireNonNull(reference, "reference");
    this.approvalId = required(approvalId, "Approval ID");
    this.fingerprint = required(fingerprint, "Fingerprint");
    this.idempotencyKey = required(idempotencyKey, "幂等键");
    this.card = Objects.requireNonNull(card, "card");
    if (!card.equals(LocalFakePeerCard.approved())) {
      throw new LocalFakePeerPreparationException();
    }
  }

  static LocalFakePeerCapsule forOperation(LocalFakePeerTaskOperation operation) {
    Objects.requireNonNull(operation, "operation");
    return new LocalFakePeerCapsule(
        operation.reference(),
        operation.approval().approvalId(),
        operation.approval().fingerprint(),
        operation.approval().idempotencyKey(),
        operation.anchor().card());
  }

  static Map<String, Object> argumentsFor(LocalFakePeerCard card) {
    Objects.requireNonNull(card, "card");
    return Map.of(
        "peer_protocol", card.manifest().protocol(),
        "peer_contract_version", card.manifest().contractVersion(),
        "peer_ref", card.manifest().identity().peerRef(),
        "task_kind", card.taskKind().name());
  }

  boolean matches(LocalFakePeerTaskOperation operation) {
    Objects.requireNonNull(operation, "operation");
    return reference.equals(operation.reference())
        && approvalId.equals(operation.approval().approvalId())
        && fingerprint.equals(operation.approval().fingerprint())
        && idempotencyKey.equals(operation.approval().idempotencyKey())
        && card.equals(operation.anchor().card())
        && ApprovalFingerprint.argumentsHash(argumentsFor(card))
            .equals(operation.approval().argumentsHash());
  }

  PeerTaskRef reference() {
    return reference;
  }

  String approvalId() {
    return approvalId;
  }

  String fingerprint() {
    return fingerprint;
  }

  String idempotencyKey() {
    return idempotencyKey;
  }

  LocalFakePeerCard card() {
    return card;
  }

  @Override
  public String toString() {
    return "LocalFakePeerCapsule[reference=<redacted>, approval=<redacted>, card=<redacted>]";
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}

final class EncryptedLocalFakePeerCapsule {
  static final int SCHEMA_VERSION = 1;
  private static final Pattern KEY_ID = Pattern.compile("[a-z0-9-]{1,32}");

  private final String keyId;
  private final byte[] nonce;
  private final byte[] ciphertext;

  EncryptedLocalFakePeerCapsule(String keyId, byte[] nonce, byte[] ciphertext) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.nonce = Objects.requireNonNull(nonce, "nonce").clone();
    this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
    if (!KEY_ID.matcher(keyId).matches() || nonce.length != 12 || ciphertext.length < 16) {
      throw new IllegalArgumentException("本地 Fake Peer Capsule 密文无效");
    }
  }

  String keyId() {
    return keyId;
  }

  byte[] nonce() {
    return nonce.clone();
  }

  byte[] ciphertext() {
    return ciphertext.clone();
  }

  @Override
  public String toString() {
    return "EncryptedLocalFakePeerCapsule[schemaVersion="
        + SCHEMA_VERSION
        + ", keyId="
        + keyId
        + ", nonce=<redacted>, ciphertext=<redacted>]";
  }
}

@FunctionalInterface
interface LocalFakePeerCapsuleCipher {
  EncryptedLocalFakePeerCapsule encrypt(
      LocalFakePeerTaskOperation operation, LocalFakePeerCapsule capsule);

  default LocalFakePeerCapsule decrypt(
      LocalFakePeerTaskOperation operation, EncryptedLocalFakePeerCapsule encrypted) {
    throw new UnsupportedOperationException("本地 Fake Peer Capsule Cipher 不支持解密");
  }
}

final class AesGcmLocalFakePeerCapsuleCipher implements LocalFakePeerCapsuleCipher {
  private static final int TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;

  private final SecretKey key;
  private final String keyId;
  private final SecureRandom random;

  AesGcmLocalFakePeerCapsuleCipher(SecretKey key, String keyId, SecureRandom random) {
    this.key = Objects.requireNonNull(key, "key");
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.random = Objects.requireNonNull(random, "random");
    new EncryptedLocalFakePeerCapsule(keyId, new byte[NONCE_BYTES], new byte[16]);
  }

  @Override
  public EncryptedLocalFakePeerCapsule encrypt(
      LocalFakePeerTaskOperation operation, LocalFakePeerCapsule capsule) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capsule, "capsule");
    if (!capsule.matches(operation)) {
      throw new LocalFakePeerPreparationException();
    }
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(operation));
      return new EncryptedLocalFakePeerCapsule(keyId, nonce, cipher.doFinal(encode(capsule)));
    } catch (GeneralSecurityException exception) {
      throw new LocalFakePeerPreparationException();
    }
  }

  @Override
  public LocalFakePeerCapsule decrypt(
      LocalFakePeerTaskOperation operation, EncryptedLocalFakePeerCapsule encrypted) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(encrypted, "encrypted");
    if (!keyId.equals(encrypted.keyId())) {
      throw new LocalFakePeerPreparationException();
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
      cipher.updateAAD(aad(operation));
      LocalFakePeerCapsule capsule = decode(cipher.doFinal(encrypted.ciphertext()));
      if (!capsule.matches(operation)) {
        throw new LocalFakePeerPreparationException();
      }
      return capsule;
    } catch (GeneralSecurityException | IOException exception) {
      throw new LocalFakePeerPreparationException();
    }
  }

  private static byte[] aad(LocalFakePeerTaskOperation operation) {
    return (EncryptedLocalFakePeerCapsule.SCHEMA_VERSION
            + "|"
            + operation.reference().value()
            + "|"
            + operation.approval().approvalId()
            + "|"
            + operation.approval().fingerprint()
            + "|"
            + operation.anchor().card().manifest().protocol()
            + "|"
            + operation.anchor().version())
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] encode(LocalFakePeerCapsule capsule) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        output.writeInt(LocalFakePeerCapsule.SCHEMA_VERSION);
        write(output, capsule.reference().value());
        write(output, capsule.approvalId());
        write(output, capsule.fingerprint());
        write(output, capsule.idempotencyKey());
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new LocalFakePeerPreparationException();
    }
  }

  private static LocalFakePeerCapsule decode(byte[] plaintext) throws IOException {
    try (var input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
      if (input.readInt() != LocalFakePeerCapsule.SCHEMA_VERSION) {
        throw new IOException("不支持的本地 Fake Peer Capsule 版本");
      }
      LocalFakePeerCapsule capsule =
          new LocalFakePeerCapsule(
              PeerTaskRef.parse(read(input)),
              read(input),
              read(input),
              read(input),
              new LocalFakePeerCard(
                  LocalFakePeerManifest.approved(), LocalFakePeerTaskKind.LOCAL_FAKE_TASK));
      if (input.available() != 0) {
        throw new IOException("本地 Fake Peer Capsule 编码尾部无效");
      }
      return capsule;
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String read(DataInputStream input) throws IOException {
    int size = input.readInt();
    if (size < 0 || size > 65_536) {
      throw new IOException("本地 Fake Peer Capsule 字段长度无效");
    }
    byte[] bytes = input.readNBytes(size);
    if (bytes.length != size) {
      throw new IOException("本地 Fake Peer Capsule 字段截断");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
