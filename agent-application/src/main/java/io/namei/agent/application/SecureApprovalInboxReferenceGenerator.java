package io.namei.agent.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SecureApprovalInboxReferenceGenerator
    implements ApprovalInboxReferenceGenerator {
  private static final int RANDOM_BYTES = 16;

  private final SecureRandom random;

  public SecureApprovalInboxReferenceGenerator() {
    this(new SecureRandom());
  }

  SecureApprovalInboxReferenceGenerator(SecureRandom random) {
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public ApprovalInboxReference next() {
    byte[] bytes = new byte[RANDOM_BYTES];
    random.nextBytes(bytes);
    try {
      return ApprovalInboxReference.of(
          Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }
}
