package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;

public interface SessionRepository {
  SessionSnapshot load(String sessionId);

  void appendTurn(String sessionId, PersistedTurn turn);
}
