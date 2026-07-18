package io.namei.agent.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque encrypted capsule safe to persist only inside the isolated operation store. */
public record EncryptedPendingOperationCapsule(
    int schemaVersion, String keyId, byte[] nonce, byte[] ciphertext) {
  public static final int SCHEMA_VERSION = 1;
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public EncryptedPendingOperationCapsule {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("不支持的加密胶囊版本");
    }
    keyId = Objects.requireNonNull(keyId, "keyId").strip();
    if (!KEY_ID.matcher(keyId).matches()) {
      throw new IllegalArgumentException("胶囊 Key ID 格式无效");
    }
    nonce = copy(nonce, "nonce");
    if (nonce.length != 12) {
      throw new IllegalArgumentException("AES-GCM Nonce 必须为 96 bit");
    }
    ciphertext = copy(ciphertext, "ciphertext");
    if (ciphertext.length < 16) {
      throw new IllegalArgumentException("AES-GCM 密文不完整");
    }
  }

  @Override
  public byte[] nonce() {
    return nonce.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  @Override
  public String toString() {
    return "EncryptedPendingOperationCapsule[schemaVersion="
        + schemaVersion
        + ", keyId=<redacted>, nonce=<redacted>, ciphertext=<redacted>]";
  }

  private static byte[] copy(byte[] value, String field) {
    Objects.requireNonNull(value, field);
    return Arrays.copyOf(value, value.length);
  }
}
