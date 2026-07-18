package io.namei.agent.application;

import io.namei.agent.kernel.proactive.SubagentRequest;

/** Isolated computation surface: no application ports are supplied to a subagent. */
@FunctionalInterface
public interface SubagentTask {
  String execute(SubagentRequest request);
}
