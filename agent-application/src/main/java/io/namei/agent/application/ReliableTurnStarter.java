package io.namei.agent.application;

import java.util.Objects;
import java.util.regex.Pattern;

@FunctionalInterface
public interface ReliableTurnStarter {
  Thread start(String name, Runnable task);

  static ReliableTurnStarter virtualThreads() {
    return (name, task) -> {
      if (name == null || !WorkerName.PATTERN.matcher(name).matches()) {
        throw new IllegalArgumentException("可靠渠道 Worker 名称不合法");
      }
      return Thread.ofVirtual().name(name).start(Objects.requireNonNull(task, "task"));
    };
  }

  final class WorkerName {
    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private WorkerName() {}
  }
}
