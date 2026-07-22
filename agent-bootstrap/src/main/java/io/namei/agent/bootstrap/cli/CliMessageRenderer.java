package io.namei.agent.bootstrap.cli;

import io.namei.agent.kernel.channel.OutboundMessage;
import java.util.Objects;

final class CliMessageRenderer {
  private final CliOutput output;
  private final StringBuilder preview = new StringBuilder();
  private boolean terminal;

  CliMessageRenderer(CliOutput output) {
    this.output = Objects.requireNonNull(output, "output");
  }

  void accept(OutboundMessage message) {
    Objects.requireNonNull(message, "message");
    if (terminal) {
      throw new IllegalStateException("CLI 已经消费终态");
    }
    switch (message.type()) {
      case TURN_STARTED -> {
        // STARTED 事件有意不渲染。
      }
      case CONTENT_DELTA -> {
        output.writeStdout(message.content());
        preview.append(message.content());
      }
      case TURN_COMPLETED -> {
        renderCompletion(message.content());
        terminal = true;
      }
      case TURN_CANCELLED, TURN_FAILED -> {
        output.writeStderr(message.code() + "\n");
        terminal = true;
      }
    }
  }

  boolean isTerminal() {
    return terminal;
  }

  private void renderCompletion(String authoritativeContent) {
    if (preview.isEmpty()) {
      output.writeStdout(authoritativeContent + "\n");
      return;
    }
    if (preview.toString().equals(authoritativeContent)) {
      output.writeStdout("\n");
      return;
    }
    output.writeStdout("\n" + authoritativeContent + "\n");
  }
}
