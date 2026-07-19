package io.namei.agent.application;

import io.namei.agent.kernel.proactive.ProactiveSourceItem;
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

/** Sensitive P2 payload. It is only passed to a cipher and never rendered by a public outcome. */
final class ProactiveDeliveryCapsule {
  static final int SCHEMA_VERSION = 1;

  private final ProactiveDeliveryOperationReference reference;
  private final String approvalId;
  private final String fingerprint;
  private final String idempotencyKey;
  private final FakeProactiveRecipientReference recipient;
  private final ProactiveSourceItem source;
  private final String jobRef;
  private final String targetHash;

  ProactiveDeliveryCapsule(
      ProactiveDeliveryOperationReference reference,
      String approvalId,
      String fingerprint,
      String idempotencyKey,
      FakeProactiveRecipientReference recipient,
      ProactiveSourceItem source,
      String jobRef,
      String targetHash) {
    this.reference = Objects.requireNonNull(reference, "reference");
    this.approvalId = required(approvalId, "Approval ID");
    this.fingerprint = required(fingerprint, "Fingerprint");
    this.idempotencyKey = required(idempotencyKey, "幂等键");
    this.recipient = Objects.requireNonNull(recipient, "recipient");
    this.source = Objects.requireNonNull(source, "source");
    this.jobRef = required(jobRef, "Job Ref");
    this.targetHash = required(targetHash, "target hash");
  }

  static ProactiveDeliveryCapsule forOperation(
      ProactiveDeliveryOperation operation,
      FakeProactiveRecipientReference recipient,
      ProactiveSourceItem source) {
    Objects.requireNonNull(operation, "operation");
    return new ProactiveDeliveryCapsule(
        operation.reference(),
        operation.approval().approvalId(),
        operation.approval().fingerprint(),
        operation.approval().idempotencyKey(),
        recipient,
        source,
        operation.anchor().jobRef().value(),
        operation.anchor().targetHash());
  }

  boolean matches(ProactiveDeliveryOperation operation) {
    Objects.requireNonNull(operation, "operation");
    return reference.equals(operation.reference())
        && approvalId.equals(operation.approval().approvalId())
        && fingerprint.equals(operation.approval().fingerprint())
        && idempotencyKey.equals(operation.approval().idempotencyKey())
        && jobRef.equals(operation.anchor().jobRef().value())
        && targetHash.equals(operation.anchor().targetHash())
        && ApprovalFingerprint.argumentsHash(arguments())
            .equals(operation.approval().argumentsHash());
  }

  FakeProactiveRecipientReference recipient() {
    return recipient;
  }

  ProactiveSourceItem source() {
    return source;
  }

  String jobRef() {
    return jobRef;
  }

  String targetHash() {
    return targetHash;
  }

  Map<String, Object> arguments() {
    return argumentsFor(recipient, source, jobRef, targetHash);
  }

  static Map<String, Object> argumentsFor(
      FakeProactiveRecipientReference recipient,
      ProactiveSourceItem source,
      String jobRef,
      String targetHash) {
    Objects.requireNonNull(recipient, "recipient");
    Objects.requireNonNull(source, "source");
    return Map.of(
        "job_ref", jobRef,
        "recipient_ref", recipient.value(),
        "source_ref", source.sourceRef(),
        "source_text", source.safeText(),
        "target_hash", targetHash);
  }

  ProactiveDeliveryOperationReference reference() {
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

  @Override
  public String toString() {
    return "ProactiveDeliveryCapsule[reference=<redacted>, approval=<redacted>, recipient=<redacted>, source=<redacted>]";
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}

final class EncryptedProactiveDeliveryCapsule {
  static final int SCHEMA_VERSION = 1;
  private static final Pattern KEY_ID = Pattern.compile("[a-z0-9-]{1,32}");

  private final String keyId;
  private final byte[] nonce;
  private final byte[] ciphertext;

  EncryptedProactiveDeliveryCapsule(String keyId, byte[] nonce, byte[] ciphertext) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.nonce = Objects.requireNonNull(nonce, "nonce").clone();
    this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
    if (!KEY_ID.matcher(keyId).matches() || nonce.length != 12 || ciphertext.length < 16) {
      throw new IllegalArgumentException("主动投递 Capsule 密文无效");
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
    return "EncryptedProactiveDeliveryCapsule[schemaVersion="
        + SCHEMA_VERSION
        + ", keyId="
        + keyId
        + ", nonce=<redacted>, ciphertext=<redacted>]";
  }
}

@FunctionalInterface
interface ProactiveDeliveryCapsuleCipher {
  EncryptedProactiveDeliveryCapsule encrypt(
      ProactiveDeliveryOperation operation, ProactiveDeliveryCapsule capsule);

  default ProactiveDeliveryCapsule decrypt(
      ProactiveDeliveryOperation operation, EncryptedProactiveDeliveryCapsule encrypted) {
    throw new UnsupportedOperationException("主动投递 Capsule Cipher 不支持解密");
  }
}

final class AesGcmProactiveDeliveryCapsuleCipher implements ProactiveDeliveryCapsuleCipher {
  private static final int TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;

  private final SecretKey key;
  private final String keyId;
  private final SecureRandom random;

  AesGcmProactiveDeliveryCapsuleCipher(SecretKey key, String keyId, SecureRandom random) {
    this.key = Objects.requireNonNull(key, "key");
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.random = Objects.requireNonNull(random, "random");
    new EncryptedProactiveDeliveryCapsule(keyId, new byte[NONCE_BYTES], new byte[16]);
  }

  @Override
  public EncryptedProactiveDeliveryCapsule encrypt(
      ProactiveDeliveryOperation operation, ProactiveDeliveryCapsule capsule) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capsule, "capsule");
    if (!capsule.matches(operation)) {
      throw new ProactiveDeliveryPreparationException();
    }
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(operation));
      return new EncryptedProactiveDeliveryCapsule(keyId, nonce, cipher.doFinal(encode(capsule)));
    } catch (GeneralSecurityException exception) {
      throw new ProactiveDeliveryPreparationException(exception);
    }
  }

  @Override
  public ProactiveDeliveryCapsule decrypt(
      ProactiveDeliveryOperation operation, EncryptedProactiveDeliveryCapsule encrypted) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(encrypted, "encrypted");
    if (!keyId.equals(encrypted.keyId())) {
      throw new ProactiveDeliveryPreparationException();
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
      cipher.updateAAD(aad(operation));
      ProactiveDeliveryCapsule capsule = decode(cipher.doFinal(encrypted.ciphertext()));
      if (!capsule.matches(operation)) {
        throw new ProactiveDeliveryPreparationException();
      }
      return capsule;
    } catch (GeneralSecurityException | IOException exception) {
      throw new ProactiveDeliveryPreparationException(exception);
    }
  }

  private static byte[] aad(ProactiveDeliveryOperation operation) {
    return (EncryptedProactiveDeliveryCapsule.SCHEMA_VERSION
            + "|"
            + operation.reference().value()
            + "|"
            + operation.approval().approvalId()
            + "|"
            + operation.approval().fingerprint()
            + "|"
            + operation.anchor().jobRef().value()
            + "|"
            + operation.anchor().targetHash()
            + "|"
            + operation.anchor().version())
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] encode(ProactiveDeliveryCapsule capsule) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        output.writeInt(ProactiveDeliveryCapsule.SCHEMA_VERSION);
        write(output, capsule.reference().value());
        write(output, capsule.approvalId());
        write(output, capsule.fingerprint());
        write(output, capsule.idempotencyKey());
        write(output, capsule.recipient().value());
        write(output, capsule.source().sourceRef());
        write(output, capsule.source().safeText());
        write(output, capsule.jobRef());
        write(output, capsule.targetHash());
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new ProactiveDeliveryPreparationException(exception);
    }
  }

  private static ProactiveDeliveryCapsule decode(byte[] plaintext) throws IOException {
    try (var input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
      if (input.readInt() != ProactiveDeliveryCapsule.SCHEMA_VERSION) {
        throw new IOException("不支持的主动投递 Capsule 版本");
      }
      ProactiveDeliveryCapsule capsule =
          new ProactiveDeliveryCapsule(
              ProactiveDeliveryOperationReference.of(read(input)),
              read(input),
              read(input),
              read(input),
              FakeProactiveRecipientReference.of(read(input)),
              ProactiveSourceItem.fixedLocal(
                  io.namei.agent.kernel.proactive.ProactiveSourceKind.FIXED_LOCAL,
                  read(input),
                  read(input)),
              read(input),
              read(input));
      if (input.available() != 0) {
        throw new IOException("主动投递 Capsule 编码尾部无效");
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
      throw new IOException("主动投递 Capsule 字段长度无效");
    }
    byte[] bytes = input.readNBytes(size);
    if (bytes.length != size) {
      throw new IOException("主动投递 Capsule 字段截断");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }
}

final class ProactiveDeliveryPreparationException extends RuntimeException {
  ProactiveDeliveryPreparationException() {
    super("PROACTIVE_DELIVERY_PREPARATION_FAILED");
  }

  ProactiveDeliveryPreparationException(Throwable cause) {
    super("PROACTIVE_DELIVERY_PREPARATION_FAILED", cause);
  }
}
