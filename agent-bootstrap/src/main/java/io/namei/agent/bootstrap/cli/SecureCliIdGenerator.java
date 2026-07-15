package io.namei.agent.bootstrap.cli;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

public final class SecureCliIdGenerator implements CliIdGenerator {
  private final SecureRandom random;

  public SecureCliIdGenerator() {
    this(new SecureRandom());
  }

  SecureCliIdGenerator(SecureRandom random) {
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public String newMessageId() {
    return "message-" + random128BitId();
  }

  @Override
  public String newTurnId() {
    return "turn-" + random128BitId();
  }

  private String random128BitId() {
    var bytes = new byte[16];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
