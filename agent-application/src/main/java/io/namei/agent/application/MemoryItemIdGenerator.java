package io.namei.agent.application;

@FunctionalInterface
public interface MemoryItemIdGenerator {
  String newMemoryItemId();
}
