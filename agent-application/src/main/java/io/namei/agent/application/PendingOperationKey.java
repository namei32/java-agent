package io.namei.agent.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** 仅在 Pending Operation Cipher 边界使用的 AES-256 Key 材料。 */
public record PendingOperationKey(String keyId, SecretKey key) {
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public PendingOperationKey {
    keyId = Objects.requireNonNull(keyId, "keyId").strip();
    if (!KEY_ID.matcher(keyId).matches()) {
      throw new IllegalArgumentException("Pending Operation Key ID 格式无效");
    }
    Objects.requireNonNull(key, "key");
    byte[] encoded = key.getEncoded();
    if (!"AES".equalsIgnoreCase(key.getAlgorithm()) || encoded == null || encoded.length != 32) {
      throw new IllegalArgumentException("Pending Operation Key 必须是 AES-256");
    }
    key = new SecretKeySpec(Arrays.copyOf(encoded, encoded.length), "AES");
    Arrays.fill(encoded, (byte) 0);
  }

  @Override
  public SecretKey key() {
    byte[] encoded = key.getEncoded();
    try {
      return new SecretKeySpec(encoded, "AES");
    } finally {
      Arrays.fill(encoded, (byte) 0);
    }
  }

  @Override
  public String toString() {
    return "PendingOperationKey[keyId=<redacted>, key=<redacted>]";
  }
}
