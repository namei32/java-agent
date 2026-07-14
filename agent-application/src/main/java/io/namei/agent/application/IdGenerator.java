package io.namei.agent.application;

interface IdGenerator {
  String newApprovalId();

  String newIdempotencyKey();
}
