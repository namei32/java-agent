package io.namei.agent.application;

import io.namei.agent.kernel.proactive.SubagentRequest;

/** 隔离的计算入口：不会向 Subagent 提供任何 Application Port。 */
@FunctionalInterface
public interface SubagentTask {
  String execute(SubagentRequest request);
}
