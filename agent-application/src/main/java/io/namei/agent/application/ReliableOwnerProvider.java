package io.namei.agent.application;

@FunctionalInterface
public interface ReliableOwnerProvider {
  String ownerId();
}
