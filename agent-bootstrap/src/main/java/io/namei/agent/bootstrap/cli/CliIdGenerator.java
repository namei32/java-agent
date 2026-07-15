package io.namei.agent.bootstrap.cli;

public interface CliIdGenerator {
  String newMessageId();

  String newTurnId();
}
