package io.namei.agent.bootstrap.cli;

import java.util.Objects;

public final class CliInputException extends RuntimeException {
  public CliInputException(String safeMessage) {
    super(Objects.requireNonNull(safeMessage, "safeMessage"));
  }

  public CliInputException(String safeMessage, Throwable failure) {
    this(safeMessage);
    Objects.requireNonNull(failure, "failure");
  }
}
