package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import io.namei.agent.kernel.proactive.ProactiveSourceKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Sensitive P6 payload. It has no public projection and may be read only by the dedicated write
 * capability after an approved operation reserves consumption.
 */
final class ProactiveMemoryNoteWriteCapsule {
  private final ProactiveMemoryNoteWriteOperationReference reference;
  private final String approvalId;
  private final String fingerprint;
  private final String idempotencyKey;
  private final ProactiveSourceItem source;
  private final String jobRef;
  private final String targetHash;
  private final Instant happenedAt;

  ProactiveMemoryNoteWriteCapsule(
      ProactiveMemoryNoteWriteOperationReference reference,
      String approvalId,
      String fingerprint,
      String idempotencyKey,
      ProactiveSourceItem source,
      String jobRef,
      String targetHash,
      Instant happenedAt) {
    this.reference = Objects.requireNonNull(reference, "reference");
    this.approvalId = required(approvalId, "Approval ID");
    this.fingerprint = required(fingerprint, "Fingerprint");
    this.idempotencyKey = required(idempotencyKey, "幂等键");
    this.source = Objects.requireNonNull(source, "source");
    this.jobRef = required(jobRef, "Job Ref");
    this.targetHash = required(targetHash, "Target Hash");
    this.happenedAt = Objects.requireNonNull(happenedAt, "happenedAt");
  }

  static ProactiveMemoryNoteWriteCapsule forOperation(
      ProactiveMemoryNoteWriteOperation operation, ProactiveSourceItem source, Instant happenedAt) {
    Objects.requireNonNull(operation, "operation");
    return new ProactiveMemoryNoteWriteCapsule(
        operation.reference(),
        operation.approval().approvalId(),
        operation.approval().fingerprint(),
        operation.approval().idempotencyKey(),
        source,
        operation.anchor().jobRef().value(),
        operation.anchor().targetHash(),
        happenedAt);
  }

  boolean matches(ProactiveMemoryNoteWriteOperation operation) {
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

  static Map<String, Object> argumentsFor(
      ProactiveSourceItem source, String jobRef, String targetHash, Instant happenedAt) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(happenedAt, "happenedAt");
    return Map.of(
        "memory_type", MemoryType.NOTE.name(),
        "scope_binding",
            ProactiveMemoryNoteWriteScope.derive(ProactiveJobRef.parse(jobRef), targetHash)
                .binding(),
        "source_ref", source.sourceRef(),
        "source_text", source.safeText(),
        "happened_at", happenedAt.toString());
  }

  Map<String, Object> arguments() {
    return argumentsFor(source, jobRef, targetHash, happenedAt);
  }

  ProactiveMemoryNoteWriteOperationReference reference() {
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

  String jobRef() {
    return jobRef;
  }

  String targetHash() {
    return targetHash;
  }

  ProactiveSourceItem source() {
    return source;
  }

  Instant happenedAt() {
    return happenedAt;
  }

  @Override
  public String toString() {
    return "ProactiveMemoryNoteWriteCapsule[reference=<redacted>, approval=<redacted>, source=<redacted>]";
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return normalized;
  }
}

final class EncryptedProactiveMemoryNoteWriteCapsule {
  static final int SCHEMA_VERSION = 1;
  private static final Pattern KEY_ID = Pattern.compile("[a-z0-9-]{1,32}");

  private final String keyId;
  private final byte[] nonce;
  private final byte[] ciphertext;

  EncryptedProactiveMemoryNoteWriteCapsule(String keyId, byte[] nonce, byte[] ciphertext) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.nonce = Objects.requireNonNull(nonce, "nonce").clone();
    this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
    if (!KEY_ID.matcher(keyId).matches() || nonce.length != 12 || ciphertext.length < 16) {
      throw new IllegalArgumentException("主动 NOTE 写入 Capsule 密文无效");
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
    return "EncryptedProactiveMemoryNoteWriteCapsule[schemaVersion="
        + SCHEMA_VERSION
        + ", keyId="
        + keyId
        + ", nonce=<redacted>, ciphertext=<redacted>]";
  }
}

@FunctionalInterface
interface ProactiveMemoryNoteWriteCapsuleCipher {
  EncryptedProactiveMemoryNoteWriteCapsule encrypt(
      ProactiveMemoryNoteWriteOperation operation, ProactiveMemoryNoteWriteCapsule capsule);

  default ProactiveMemoryNoteWriteCapsule decrypt(
      ProactiveMemoryNoteWriteOperation operation,
      EncryptedProactiveMemoryNoteWriteCapsule encrypted) {
    throw new UnsupportedOperationException("主动 NOTE 写入 Capsule Cipher 不支持解密");
  }
}

final class AesGcmProactiveMemoryNoteWriteCapsuleCipher
    implements ProactiveMemoryNoteWriteCapsuleCipher {
  private static final int TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;

  private final SecretKey key;
  private final String keyId;
  private final SecureRandom random;

  AesGcmProactiveMemoryNoteWriteCapsuleCipher(SecretKey key, String keyId, SecureRandom random) {
    this.key = Objects.requireNonNull(key, "key");
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.random = Objects.requireNonNull(random, "random");
    new EncryptedProactiveMemoryNoteWriteCapsule(keyId, new byte[NONCE_BYTES], new byte[16]);
  }

  @Override
  public EncryptedProactiveMemoryNoteWriteCapsule encrypt(
      ProactiveMemoryNoteWriteOperation operation, ProactiveMemoryNoteWriteCapsule capsule) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capsule, "capsule");
    if (!capsule.matches(operation)) {
      throw new ProactiveMemoryNoteWritePreparationException();
    }
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(operation));
      return new EncryptedProactiveMemoryNoteWriteCapsule(
          keyId, nonce, cipher.doFinal(encode(capsule)));
    } catch (GeneralSecurityException exception) {
      throw new ProactiveMemoryNoteWritePreparationException(exception);
    }
  }

  @Override
  public ProactiveMemoryNoteWriteCapsule decrypt(
      ProactiveMemoryNoteWriteOperation operation,
      EncryptedProactiveMemoryNoteWriteCapsule encrypted) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(encrypted, "encrypted");
    if (!keyId.equals(encrypted.keyId())) {
      throw new ProactiveMemoryNoteWritePreparationException();
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
      cipher.updateAAD(aad(operation));
      ProactiveMemoryNoteWriteCapsule capsule = decode(cipher.doFinal(encrypted.ciphertext()));
      if (!capsule.matches(operation)) {
        throw new ProactiveMemoryNoteWritePreparationException();
      }
      return capsule;
    } catch (GeneralSecurityException | IOException exception) {
      throw new ProactiveMemoryNoteWritePreparationException(exception);
    }
  }

  private static byte[] aad(ProactiveMemoryNoteWriteOperation operation) {
    return (EncryptedProactiveMemoryNoteWriteCapsule.SCHEMA_VERSION
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

  private static byte[] encode(ProactiveMemoryNoteWriteCapsule capsule) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        output.writeInt(EncryptedProactiveMemoryNoteWriteCapsule.SCHEMA_VERSION);
        write(output, capsule.reference().value());
        write(output, capsule.approvalId());
        write(output, capsule.fingerprint());
        write(output, capsule.idempotencyKey());
        write(output, capsule.source().sourceRef());
        write(output, capsule.source().safeText());
        write(output, capsule.jobRef());
        write(output, capsule.targetHash());
        write(output, capsule.happenedAt().toString());
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new ProactiveMemoryNoteWritePreparationException(exception);
    }
  }

  private static ProactiveMemoryNoteWriteCapsule decode(byte[] plaintext) throws IOException {
    try (var input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
      if (input.readInt() != EncryptedProactiveMemoryNoteWriteCapsule.SCHEMA_VERSION) {
        throw new IOException("不支持的主动 NOTE 写入 Capsule 版本");
      }
      ProactiveMemoryNoteWriteCapsule capsule =
          new ProactiveMemoryNoteWriteCapsule(
              ProactiveMemoryNoteWriteOperationReference.of(read(input)),
              read(input),
              read(input),
              read(input),
              ProactiveSourceItem.fixedLocal(
                  ProactiveSourceKind.FIXED_LOCAL, read(input), read(input)),
              read(input),
              read(input),
              Instant.parse(read(input)));
      if (input.available() != 0) {
        throw new IOException("主动 NOTE 写入 Capsule 编码尾部无效");
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
      throw new IOException("主动 NOTE 写入 Capsule 字段长度无效");
    }
    byte[] bytes = input.readNBytes(size);
    if (bytes.length != size) {
      throw new IOException("主动 NOTE 写入 Capsule 字段截断");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }
}

final class ProactiveMemoryNoteWritePreparationException extends RuntimeException {
  ProactiveMemoryNoteWritePreparationException() {
    super("PROACTIVE_MEMORY_NOTE_WRITE_PREPARATION_FAILED");
  }

  ProactiveMemoryNoteWritePreparationException(Throwable cause) {
    super("PROACTIVE_MEMORY_NOTE_WRITE_PREPARATION_FAILED", cause);
  }
}
