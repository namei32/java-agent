package io.namei.agent.application;

@FunctionalInterface
public interface ReliableTurnProcessor {
  void process(ReliableTurnContext context, TurnCancellation cancellation);
}
