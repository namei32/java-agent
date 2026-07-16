package io.namei.agent.bootstrap.cli;

@FunctionalInterface
public interface CliThreadStarter {
  Thread start(String name, Runnable task);
}
