package io.namei.agent.bootstrap.telegram;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

public final class SecureTelegramIdGenerator implements TelegramIdGenerator {
  private final SecureRandom random;

  public SecureTelegramIdGenerator() {
    this(new SecureRandom());
  }

  SecureTelegramIdGenerator(SecureRandom random) {
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public String newTurnId() {
    var bytes = new byte[16];
    random.nextBytes(bytes);
    return "turn-" + HexFormat.of().formatHex(bytes);
  }
}
