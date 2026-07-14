package io.namei.agent.application;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class SecureIdGenerator implements IdGenerator {
  private final SecureRandom random;

  public SecureIdGenerator() {
    this(new SecureRandom());
  }

  SecureIdGenerator(SecureRandom random) {
    this.random = java.util.Objects.requireNonNull(random, "random");
  }

  @Override
  public String newTurnId() {
    return "turn-" + random128BitId();
  }

  @Override
  public String newApprovalId() {
    return "approval-" + random128BitId();
  }

  @Override
  public String newIdempotencyKey() {
    return "operation-" + random128BitId();
  }

  private String random128BitId() {
    var bytes = new byte[16];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
