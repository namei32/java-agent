package io.namei.agent.kernel.port;

import io.namei.agent.kernel.memory.EmbeddingRequest;
import io.namei.agent.kernel.memory.EmbeddingResult;

@FunctionalInterface
public interface EmbeddingPort {
  EmbeddingResult embed(EmbeddingRequest request);
}
