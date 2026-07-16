package io.namei.agent.bootstrap.cli;

@FunctionalInterface
public interface CliInput {
  /** Returns the next line, or {@code null} at EOF. */
  String readLine();
}
