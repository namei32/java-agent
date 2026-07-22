package io.namei.agent.application;

import io.namei.agent.kernel.proactive.DriftRequest;
import io.namei.agent.kernel.proactive.DriftResult;

/** 该狭窄 Port 不具备 Workspace、网络、Tool、投递、Memory 或 Session 能力。 */
@FunctionalInterface
public interface ReadOnlyDriftProbe {
  DriftResult inspect(DriftRequest request);
}
