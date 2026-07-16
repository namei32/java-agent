package io.namei.agent.bootstrap.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class Utf8CliOutput implements CliOutput {
  private final OutputStream stdout;
  private final OutputStream stderr;

  public Utf8CliOutput(OutputStream stdout, OutputStream stderr) {
    this.stdout = Objects.requireNonNull(stdout, "stdout");
    this.stderr = Objects.requireNonNull(stderr, "stderr");
  }

  @Override
  public void writeStdout(String text) {
    write(stdout, text, "CLI stdout 写入失败");
  }

  @Override
  public void writeStderr(String text) {
    write(stderr, text, "CLI stderr 写入失败");
  }

  private static synchronized void write(OutputStream target, String text, String safeMessage) {
    Objects.requireNonNull(text, "text");
    try {
      target.write(text.getBytes(StandardCharsets.UTF_8));
      target.flush();
      if (target instanceof PrintStream printStream && printStream.checkError()) {
        throw new IOException("PrintStream reported an output failure");
      }
    } catch (IOException failure) {
      throw new CliOutputException(safeMessage, failure);
    }
  }
}
