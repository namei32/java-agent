package io.namei.agent.application;

import java.util.function.Supplier;

public interface SessionExecutionGate {
  <T> T execute(String sessionId, Supplier<T> action);
}
