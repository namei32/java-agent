package io.namei.agent.kernel.port;

import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 本地持久 Scheduler Port；Job 执行有意置于 Store 事务之外。 */
public interface ProactiveJobStore {
  void schedule(ScheduledJob job);

  default Optional<ScheduledJob> find(ProactiveJobRef jobRef) {
    return Optional.empty();
  }

  Optional<ProactiveJobLease> claimNext(Instant now, String ownerId, Duration leaseDuration);

  Optional<ProactiveJobLease> markRunning(ProactiveJobLease lease, Instant now);

  boolean complete(ProactiveJobLease lease, ProactiveJobState terminalState, Instant completedAt);

  int recoverExpired(Instant now, int limit);
}
