package io.namei.agent.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SecurePendingOperationReferenceGenerator
    implements PendingOperationReferenceGenerator {
  private static final int RANDOM_BYTES = 16;

  private final SecureRandom random;

  public SecurePendingOperationReferenceGenerator() {
    this(new SecureRandom());
  }

  SecurePendingOperationReferenceGenerator(SecureRandom random) {
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public PendingOperationReference next() {
    byte[] bytes = new byte[RANDOM_BYTES];
    random.nextBytes(bytes);
    try {
      return PendingOperationReference.of(
          Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }
}
