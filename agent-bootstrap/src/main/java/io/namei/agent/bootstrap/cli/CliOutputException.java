package io.namei.agent.bootstrap.cli;

import java.util.Objects;

public final class CliOutputException extends RuntimeException {
  public CliOutputException(String safeMessage, Throwable failure) {
    super(Objects.requireNonNull(safeMessage, "safeMessage"));
    Objects.requireNonNull(failure, "failure");
  }
}
