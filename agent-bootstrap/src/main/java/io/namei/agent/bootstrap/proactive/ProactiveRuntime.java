package io.namei.agent.bootstrap.proactive;

import io.namei.agent.adapter.sqlite.JdbcProactiveJobStore;
import io.namei.agent.adapter.sqlite.ProactiveSchemaInitializer;
import io.namei.agent.application.ProactiveAudit;
import io.namei.agent.application.ProactiveDelivery;
import io.namei.agent.application.ProactiveJobRunner;
import io.namei.agent.application.ProactiveSafetyGate;
import io.namei.agent.application.ProactiveScheduler;
import io.namei.agent.application.ProactiveSchedulerSettings;
import io.namei.agent.application.ProactiveTargetBusyProbe;
import io.namei.agent.application.ReliableTurnStarter;
import io.namei.agent.bootstrap.plugin.PluginRuntime;
import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginTapEvent;
import io.namei.agent.kernel.plugin.PluginTapOutcome;
import io.namei.agent.kernel.proactive.ProactiveDecision;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import io.namei.agent.kernel.proactive.ProactiveStableCode;
import io.namei.agent.kernel.proactive.ScheduledJob;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * Bootstrap boundary for the default-off local scheduler. It has no provider, channel or tool path.
 */
public final class ProactiveRuntime implements AutoCloseable {
  private final ProactiveScheduler scheduler;

  private ProactiveRuntime(ProactiveScheduler scheduler) {
    this.scheduler = scheduler;
  }

  public static ProactiveRuntime start(
      ProactiveProperties properties, Path workspace, PluginRuntime plugins) {
    return start(properties, workspace, plugins, Clock.systemUTC());
  }

  static ProactiveRuntime start(
      ProactiveProperties properties, Path workspace, PluginRuntime plugins, Clock clock) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(plugins, "plugins");
    Objects.requireNonNull(clock, "clock");
    if (properties.mode() == ProactiveMode.DISABLED) {
      return new ProactiveRuntime(null);
    }
    Path database =
        Objects.requireNonNull(workspace, "workspace").resolve("proactive/proactive-runtime.db");
    var schema = new ProactiveSchemaInitializer(database, 5_000);
    schema.initialize();
    var store = new JdbcProactiveJobStore(schema);
    for (ProactiveProperties.Plan plan : properties.plans()) {
      registerAllowlistedPlan(store, plan.toJob());
    }
    var runner =
        new ProactiveJobRunner(
            new ProactiveSafetyGate(
                true,
                ProactiveTargetBusyProbe.none(),
                ignored -> false,
                clock,
                properties.cooldown()),
            (lease, cancellation) ->
                ProactiveDecision.skipped(ProactiveStableCode.PROACTIVE_DISABLED),
            ProactiveDelivery.noop(),
            ProactiveAudit.disabled());
    var scheduler =
        new ProactiveScheduler(
            store,
            runner,
            ReliableTurnStarter.virtualThreads(),
            clock,
            new ProactiveSchedulerSettings(
                properties.ownerId(),
                properties.leaseDuration(),
                properties.idleWait(),
                32,
                properties.shutdownTimeout()),
            (lease, terminal) -> plugins.publishProactive(tap(lease.job(), terminal)));
    try {
      scheduler.start();
      return new ProactiveRuntime(scheduler);
    } catch (RuntimeException failure) {
      scheduler.close();
      throw failure;
    }
  }

  public boolean active() {
    return scheduler != null;
  }

  @Override
  public void close() {
    if (scheduler != null) {
      scheduler.close();
    }
  }

  private static void registerAllowlistedPlan(JdbcProactiveJobStore store, ScheduledJob job) {
    var existing = store.find(job.jobRef());
    if (existing.isEmpty()) {
      store.schedule(job);
      return;
    }
    ScheduledJob persisted = existing.orElseThrow();
    if (persisted.schedule().equals(job.schedule())
        && persisted.targetHash().equals(job.targetHash())
        && persisted.idempotencyKey().equals(job.idempotencyKey())
        && persisted.maxAttempts() == job.maxAttempts()) {
      return;
    }
    throw new IllegalStateException("Proactive allowlisted plan 与持久化契约不一致");
  }

  private static PluginTapEvent tap(ScheduledJob job, ProactiveJobState terminal) {
    PluginTapOutcome outcome =
        switch (terminal) {
          case SUCCEEDED -> PluginTapOutcome.COMPLETED;
          case FAILED -> PluginTapOutcome.FAILED;
          default -> PluginTapOutcome.SKIPPED;
        };
    return new PluginTapEvent(PluginCapability.PROACTIVE_TAP, job.targetHash(), outcome, null, 0);
  }
}
