package io.namei.agent.application.control;

@FunctionalInterface
public interface ControlEventWriter {
  void write(ControlSequencedEvent event);
}
