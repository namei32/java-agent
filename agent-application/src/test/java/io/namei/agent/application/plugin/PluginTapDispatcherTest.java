package io.namei.agent.application.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginKind;
import io.namei.agent.kernel.plugin.PluginManifest;
import io.namei.agent.kernel.plugin.PluginStableCode;
import io.namei.agent.kernel.plugin.PluginTap;
import io.namei.agent.kernel.plugin.PluginTapEvent;
import io.namei.agent.kernel.plugin.PluginTapOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PluginTapDispatcherTest {
  private static final PluginTapEvent TURN_EVENT =
      new PluginTapEvent(
          PluginCapability.TURN_TAP,
          "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          PluginTapOutcome.COMPLETED,
          null,
          12);

  @Test
  void submitsEligiblePluginsByPriorityAndIdWithoutCallingThemOnPublisherThread() {
    var executor = new ManualExecutor();
    var deadlines = new ManualDeadlineScheduler();
    var audit = new CapturingAudit();
    var calls = new ArrayList<String>();
    var dispatcher =
        new PluginTapDispatcher(
            List.of(
                binding("beta", 10, event -> calls.add("beta")),
                binding("alpha", 20, event -> calls.add("alpha")),
                binding("gamma", 10, event -> calls.add("gamma"))),
            Duration.ofSeconds(1),
            executor,
            deadlines,
            audit);

    dispatcher.publish(TURN_EVENT);

    assertThat(calls).isEmpty();
    assertThat(executor.taskCount()).isEqualTo(3);

    executor.runAll();

    assertThat(calls).containsExactly("alpha", "beta", "gamma");
    assertThat(audit.events)
        .allSatisfy(
            event -> assertThat(event.pluginHash()).doesNotContain("alpha", "beta", "gamma"));
  }

  @Test
  void timeoutFailsOnlyOnePluginAndPreventsSubsequentDispatch() {
    var executor = new ManualExecutor();
    var deadlines = new ManualDeadlineScheduler();
    var audit = new CapturingAudit();
    var dispatcher =
        new PluginTapDispatcher(
            List.of(binding("slow", 0, event -> {})),
            Duration.ofSeconds(1),
            executor,
            deadlines,
            audit);

    dispatcher.publish(TURN_EVENT);
    deadlines.fireAll();
    dispatcher.publish(TURN_EVENT);

    assertThat(dispatcher.snapshot())
        .singleElement()
        .satisfies(
            status -> {
              assertThat(status.state()).isEqualTo(PluginRuntimeState.FAILED);
              assertThat(status.lastCode()).contains(PluginStableCode.PLUGIN_TIMEOUT);
            });
    assertThat(executor.taskCount()).isOne();
    assertThat(audit.events)
        .anySatisfy(event -> assertThat(event.code()).contains(PluginStableCode.PLUGIN_TIMEOUT));
  }

  @Test
  void runtimeFailureIsolatedFromOtherPluginsAndNeverExposesPluginIdInAudit() {
    var executor = new ManualExecutor();
    var deadlines = new ManualDeadlineScheduler();
    var audit = new CapturingAudit();
    var calls = new ArrayList<String>();
    var dispatcher =
        new PluginTapDispatcher(
            List.of(
                binding(
                    "faulty",
                    0,
                    event -> {
                      throw new IllegalStateException("plugin-secret");
                    }),
                binding("healthy", 0, event -> calls.add("healthy"))),
            Duration.ofSeconds(1),
            executor,
            deadlines,
            audit);

    dispatcher.publish(TURN_EVENT);
    executor.runAll();
    dispatcher.publish(TURN_EVENT);
    executor.runAll();

    assertThat(calls).containsExactly("healthy", "healthy");
    assertThat(dispatcher.snapshot())
        .filteredOn(status -> status.pluginId().equals(PluginId.parse("faulty")))
        .singleElement()
        .satisfies(
            status -> {
              assertThat(status.state()).isEqualTo(PluginRuntimeState.FAILED);
              assertThat(status.lastCode()).contains(PluginStableCode.PLUGIN_EXECUTION_FAILED);
            });
    assertThat(audit.events.toString()).doesNotContain("faulty", "healthy", "plugin-secret");
  }

  private static PluginTapBinding binding(String id, int priority, PluginTap tap) {
    return new PluginTapBinding(
        new PluginManifest(
            1,
            PluginId.parse(id),
            "1",
            1,
            PluginKind.JAVA_SERVICE,
            List.of(PluginCapability.TURN_TAP)),
        priority,
        tap);
  }

  private static final class CapturingAudit implements PluginTapAudit {
    private final List<PluginTapAuditEvent> events = new ArrayList<>();

    @Override
    public void record(PluginTapAuditEvent event) {
      events.add(event);
    }
  }

  private static final class ManualExecutor implements PluginTaskExecutor {
    private final List<ManualTask> tasks = new ArrayList<>();

    @Override
    public PluginTask submit(Runnable task) {
      var submitted = new ManualTask(task);
      tasks.add(submitted);
      return submitted;
    }

    int taskCount() {
      return tasks.size();
    }

    void runAll() {
      List.copyOf(tasks).forEach(ManualTask::run);
    }
  }

  private static final class ManualTask implements PluginTask {
    private final Runnable task;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean done = new AtomicBoolean();

    private ManualTask(Runnable task) {
      this.task = task;
    }

    @Override
    public boolean cancel() {
      return cancelled.compareAndSet(false, true);
    }

    @Override
    public boolean isDone() {
      return done.get();
    }

    void run() {
      if (cancelled.get() || !done.compareAndSet(false, true)) {
        return;
      }
      task.run();
    }
  }

  private static final class ManualDeadlineScheduler implements PluginDeadlineScheduler {
    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public PluginDeadline schedule(Duration delay, Runnable task) {
      tasks.add(task);
      return () -> tasks.remove(task);
    }

    void fireAll() {
      List.copyOf(tasks).forEach(Runnable::run);
    }
  }
}
