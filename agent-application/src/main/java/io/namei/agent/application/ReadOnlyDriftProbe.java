package io.namei.agent.application;

import io.namei.agent.kernel.proactive.DriftRequest;
import io.namei.agent.kernel.proactive.DriftResult;

/** This narrow port has no workspace, network, tool, delivery, memory or session capability. */
@FunctionalInterface
public interface ReadOnlyDriftProbe {
  DriftResult inspect(DriftRequest request);
}
