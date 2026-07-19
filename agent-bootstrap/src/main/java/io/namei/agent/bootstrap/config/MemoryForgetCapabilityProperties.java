package io.namei.agent.bootstrap.config;

import io.namei.agent.application.PendingOperationKey;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strict, disabled-by-default configuration for encrypting the pending-operation capsule. The key
 * is never returned from {@link #toString()} or logged by this type.
 */
@ConfigurationProperties("agent.capabilities.memory-forget")
public final class MemoryForgetCapabilityProperties {
  private final MemoryForgetCapabilityMode mode;
  private final String capsuleKeyId;
  private final String capsuleKeyBase64;

  @ConstructorBinding
  public MemoryForgetCapabilityProperties(
      @DefaultValue("DISABLED") String mode,
      @DefaultValue("") String capsuleKeyId,
      @DefaultValue("") String capsuleKeyBase64) {
    this.mode = MemoryForgetCapabilityMode.parse(mode);
    this.capsuleKeyId = capsuleKeyId == null ? "" : capsuleKeyId.strip();
    this.capsuleKeyBase64 = capsuleKeyBase64 == null ? "" : capsuleKeyBase64;
  }

  public MemoryForgetCapabilityMode mode() {
    return mode;
  }

  /** Decodes a canonical standard Base64 AES-256 key only for explicit active configuration. */
  PendingOperationKey currentKey() {
    if (mode != MemoryForgetCapabilityMode.LOOPBACK_APPROVAL) {
      throw new IllegalStateException("禁用的 Memory Forget Capability 不提供密钥");
    }
    byte[] decoded = null;
    try {
      decoded = Base64.getDecoder().decode(capsuleKeyBase64);
      if (!Base64.getEncoder().encodeToString(decoded).equals(capsuleKeyBase64)) {
        throw new IllegalArgumentException("Memory Forget Capsule Key 必须为规范 Base64");
      }
      return new PendingOperationKey(capsuleKeyId, new SecretKeySpec(decoded, "AES"));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Memory Forget Capsule Key 无效", invalid);
    } finally {
      if (decoded != null) {
        Arrays.fill(decoded, (byte) 0);
      }
    }
  }

  @Override
  public String toString() {
    return "MemoryForgetCapabilityProperties[mode="
        + mode
        + ", capsuleKeyId=<redacted>, capsuleKeyBase64=<redacted>]";
  }
}
