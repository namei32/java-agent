package io.namei.agent.application;

import io.namei.agent.kernel.channel.OutboundMessage;
import java.util.List;

@FunctionalInterface
public interface ChannelTerminalProjector {
  List<String> project(OutboundMessage terminal);
}
