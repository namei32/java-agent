package io.namei.agent.application;

@FunctionalInterface
public interface ReliableTurnIdGenerator {
  String newTurnId();
}
