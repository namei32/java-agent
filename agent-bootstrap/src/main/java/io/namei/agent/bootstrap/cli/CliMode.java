package io.namei.agent.bootstrap.cli;

import java.util.Objects;

public final class CliMode {
  private static final String SWITCH = "--cli";

  private CliMode() {}

  public static boolean isRequested(String[] arguments) {
    Objects.requireNonNull(arguments, "arguments");
    for (String argument : arguments) {
      if (SWITCH.equals(argument)) {
        return true;
      }
    }
    return false;
  }
}
