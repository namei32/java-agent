package io.namei.agent.bootstrap.cli;

import java.util.Objects;

public final class CliRunnerException extends RuntimeException {
  public CliRunnerException(String safeMessage) {
    super(Objects.requireNonNull(safeMessage, "safeMessage"));
  }

  public CliRunnerException(String safeMessage, Throwable failure) {
    this(safeMessage);
    Objects.requireNonNull(failure, "failure");
  }
}
