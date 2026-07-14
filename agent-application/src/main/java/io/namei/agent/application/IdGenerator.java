package io.namei.agent.application;

public interface IdGenerator {
  String newTurnId();

  String newApprovalId();

  String newIdempotencyKey();
}
