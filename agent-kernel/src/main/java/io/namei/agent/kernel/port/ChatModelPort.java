package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;

@FunctionalInterface
public interface ChatModelPort {
  ChatModelResponse generate(ChatModelRequest request);
}
