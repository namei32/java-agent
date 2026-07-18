package io.namei.agent.adapter.sqlite;

import io.namei.agent.application.EncryptedPendingOperationCapsule;
import io.namei.agent.application.PendingOperation;
import io.namei.agent.application.PendingOperationCapsule;
import io.namei.agent.application.PendingOperationCapsuleCipher;
import io.namei.agent.application.PendingOperationCapsuleException;
import io.namei.agent.application.PendingOperationKey;
import io.namei.agent.application.PendingOperationKeyProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** AES-256-GCM adapter for the isolated pending-operation capsule boundary. */
public final class AesGcmPendingOperationCapsuleCipher implements PendingOperationCapsuleCipher {
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final PendingOperationKeyProvider keys;
  private final SecureRandom random;

  public AesGcmPendingOperationCapsuleCipher(PendingOperationKeyProvider keys) {
    this(keys, new SecureRandom());
  }

  AesGcmPendingOperationCapsuleCipher(PendingOperationKeyProvider keys, SecureRandom random) {
    this.keys = Objects.requireNonNull(keys, "keys");
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public EncryptedPendingOperationCapsule encrypt(
      PendingOperation operation, PendingOperationCapsule capsule) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capsule, "capsule");
    if (!capsule.matches(operation)) {
      throw new PendingOperationCapsuleException();
    }
    byte[] nonce = new byte[NONCE_BYTES];
    byte[] plaintext = null;
    try {
      PendingOperationKey key =
          Objects.requireNonNull(keys.current(), "current pending operation key");
      random.nextBytes(nonce);
      plaintext = CapsuleCodec.encode(capsule);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key.key(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(operation, capsule.schemaVersion()));
      byte[] ciphertext = cipher.doFinal(plaintext);
      try {
        return new EncryptedPendingOperationCapsule(
            EncryptedPendingOperationCapsule.SCHEMA_VERSION, key.keyId(), nonce, ciphertext);
      } finally {
        Arrays.fill(ciphertext, (byte) 0);
      }
    } catch (PendingOperationCapsuleException exception) {
      throw exception;
    } catch (GeneralSecurityException | RuntimeException exception) {
      throw new PendingOperationCapsuleException(exception);
    } finally {
      Arrays.fill(nonce, (byte) 0);
      if (plaintext != null) {
        Arrays.fill(plaintext, (byte) 0);
      }
    }
  }

  @Override
  public PendingOperationCapsule decrypt(
      PendingOperation operation, EncryptedPendingOperationCapsule encrypted) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(encrypted, "encrypted");
    byte[] plaintext = null;
    try {
      PendingOperationKey key =
          keys.findByKeyId(encrypted.keyId()).orElseThrow(PendingOperationCapsuleException::new);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, key.key(), new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
      cipher.updateAAD(aad(operation, PendingOperationCapsule.SCHEMA_VERSION));
      plaintext = cipher.doFinal(encrypted.ciphertext());
      PendingOperationCapsule capsule = CapsuleCodec.decode(plaintext);
      if (!capsule.matches(operation)) {
        throw new PendingOperationCapsuleException();
      }
      return capsule;
    } catch (PendingOperationCapsuleException exception) {
      throw exception;
    } catch (GeneralSecurityException | RuntimeException exception) {
      throw new PendingOperationCapsuleException(exception);
    } finally {
      if (plaintext != null) {
        Arrays.fill(plaintext, (byte) 0);
      }
    }
  }

  private static byte[] aad(PendingOperation operation, int capsuleVersion) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        output.writeInt(capsuleVersion);
        writeText(operation.reference().value(), output);
        writeText(operation.approval().fingerprint(), output);
        writeText(operation.approval().toolVersion(), output);
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("无法生成待审批操作胶囊 AAD", exception);
    }
  }

  private static void writeText(String value, DataOutputStream output) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
    Arrays.fill(encoded, (byte) 0);
  }

  private static final class CapsuleCodec {
    private static final int MAX_TEXT_BYTES = 65_536;

    private static byte[] encode(PendingOperationCapsule capsule) {
      try {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
          output.writeInt(capsule.schemaVersion());
          write(capsule.sessionId(), output);
          output.writeLong(capsule.expectedNextSequence());
          write(capsule.turnId(), output);
          write(capsule.callId(), output);
          write(capsule.toolName(), output);
          write(capsule.toolVersion(), output);
          write(capsule.risk(), output);
          write(capsule.canonicalArgumentsJson(), output);
          write(capsule.approvalId(), output);
          write(capsule.fingerprint(), output);
          write(capsule.idempotencyKey(), output);
          write(capsule.executionBoundaryVersion(), output);
        }
        return bytes.toByteArray();
      } catch (IOException exception) {
        throw new PendingOperationCapsuleException(exception);
      }
    }

    private static PendingOperationCapsule decode(byte[] encoded) {
      try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
        var capsule =
            new PendingOperationCapsule(
                input.readInt(),
                read(input),
                input.readLong(),
                read(input),
                read(input),
                read(input),
                read(input),
                read(input),
                read(input),
                read(input),
                read(input),
                read(input),
                read(input));
        if (input.read() != -1) {
          throw new PendingOperationCapsuleException();
        }
        return capsule;
      } catch (EOFException | IllegalArgumentException exception) {
        throw new PendingOperationCapsuleException(exception);
      } catch (IOException exception) {
        throw new PendingOperationCapsuleException(exception);
      }
    }

    private static void write(String value, DataOutputStream output) throws IOException {
      byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
      try {
        output.writeInt(encoded.length);
        output.write(encoded);
      } finally {
        Arrays.fill(encoded, (byte) 0);
      }
    }

    private static String read(DataInputStream input) throws IOException {
      int length = input.readInt();
      if (length < 0 || length > MAX_TEXT_BYTES) {
        throw new PendingOperationCapsuleException();
      }
      byte[] value = input.readNBytes(length);
      if (value.length != length) {
        throw new EOFException();
      }
      try {
        return new String(value, StandardCharsets.UTF_8);
      } finally {
        Arrays.fill(value, (byte) 0);
      }
    }
  }
}
