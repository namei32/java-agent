package io.namei.agent.bootstrap.telegram;

import io.namei.agent.application.ChannelTerminalProjector;
import io.namei.agent.kernel.channel.OutboundMessage;
import java.util.List;
import java.util.Objects;

public final class TelegramTerminalRenderer implements ChannelTerminalProjector {
  private final TelegramTextChunker chunker;

  public TelegramTerminalRenderer(TelegramTextChunker chunker) {
    this.chunker = Objects.requireNonNull(chunker, "chunker");
  }

  @Override
  public List<String> project(OutboundMessage terminal) {
    Objects.requireNonNull(terminal, "terminal");
    if (!terminal.type().isTerminal()) {
      throw new IllegalArgumentException("Telegram Renderer 只接受终态消息");
    }
    String text =
        switch (terminal.type()) {
          case TURN_COMPLETED -> terminal.content();
          case TURN_CANCELLED -> "请求已取消（" + terminal.code() + "）";
          case TURN_FAILED ->
              "请求失败（" + terminal.code() + "）" + (terminal.retryable() ? "，请重新发送。" : "");
          case TURN_STARTED, CONTENT_DELTA -> throw new IllegalStateException("已拒绝非终态消息");
        };
    return List.copyOf(chunker.split(text));
  }
}
