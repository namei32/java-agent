package io.namei.agent.bootstrap.cli;

public interface CliOutput {
  void writeStdout(String text);

  void writeStderr(String text);
}
