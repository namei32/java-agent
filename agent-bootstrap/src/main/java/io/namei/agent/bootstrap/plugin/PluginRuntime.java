package io.namei.agent.bootstrap.plugin;

import io.namei.agent.application.OutboundMessageObserver;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.application.plugin.PluginDeadline;
import io.namei.agent.application.plugin.PluginDeadlineScheduler;
import io.namei.agent.application.plugin.PluginTapAudit;
import io.namei.agent.application.plugin.PluginTapBinding;
import io.namei.agent.application.plugin.PluginTapDispatcher;
import io.namei.agent.application.plugin.PluginTask;
import io.namei.agent.application.plugin.PluginTaskExecutor;
import io.namei.agent.kernel.plugin.PluginTapEvent;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bootstrap 拥有的 Plugin 生命周期：Disabled 时不创建发现器、线程、进程或调度器。 */
public final class PluginRuntime implements AutoCloseable {
  private final PluginTapDispatcher dispatcher;
  private final RuntimeTaskExecutor tasks;
  private final RuntimeDeadlineScheduler deadlines;
  private final List<ExternalStdioPluginBridge> bridges;
  private final AtomicBoolean closed = new AtomicBoolean();

  private PluginRuntime(
      PluginTapDispatcher dispatcher,
      RuntimeTaskExecutor tasks,
      RuntimeDeadlineScheduler deadlines,
      List<ExternalStdioPluginBridge> bridges) {
    this.dispatcher = dispatcher;
    this.tasks = tasks;
    this.deadlines = deadlines;
    this.bridges = List.copyOf(bridges);
  }

  public static PluginRuntime start(
      PluginProperties properties,
      ToolRuntimeMode toolMode,
      JavaServicePluginDiscovery javaDiscovery,
      ExternalStdioPluginTransportFactory externalFactory) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(toolMode, "toolMode");
    Objects.requireNonNull(javaDiscovery, "javaDiscovery");
    Objects.requireNonNull(externalFactory, "externalFactory");
    if (properties.mode() == PluginMode.DISABLED) {
      return disabled();
    }
    if (toolMode != ToolRuntimeMode.READ_ONLY) {
      throw new IllegalStateException("启用 Plugin 前全局 Tool Runtime 必须为 READ_ONLY");
    }

    var bridges = new ArrayList<ExternalStdioPluginBridge>();
    try {
      List<PluginTapBinding> bindings =
          switch (properties.mode()) {
            case JAVA_SERVICE ->
                javaDiscovery.discover(PluginMode.JAVA_SERVICE, properties.javaServiceIds());
            case EXTERNAL_STDIO -> externalBindings(properties, externalFactory, bridges);
            case DISABLED -> List.of();
          };
      if (bindings.isEmpty()) {
        return disabled();
      }
      var tasks = new RuntimeTaskExecutor();
      var deadlines = new RuntimeDeadlineScheduler();
      return new PluginRuntime(
          new PluginTapDispatcher(
              bindings, properties.tapTimeout(), tasks, deadlines, PluginTapAudit.disabled()),
          tasks,
          deadlines,
          bridges);
    } catch (RuntimeException failure) {
      bridges.forEach(ExternalStdioPluginBridge::close);
      throw failure;
    }
  }

  public static PluginRuntime disabled() {
    return new PluginRuntime(null, null, null, List.of());
  }

  public boolean active() {
    return dispatcher != null && !closed.get();
  }

  public TurnLifecycleObserver lifecycleObserver() {
    return dispatcher == null
        ? TurnLifecycleObserver.noop()
        : new PluginLifecycleTapObserver(dispatcher);
  }

  public OutboundMessageObserver outboundMessageObserver() {
    return dispatcher == null
        ? OutboundMessageObserver.noop()
        : new PluginOutboundMessageObserver(dispatcher);
  }

  /** R8 可直接复用此入口发布已投影的主动任务事件。 */
  public void publishProactive(PluginTapEvent event) {
    if (dispatcher != null && !closed.get()) {
      dispatcher.publish(event);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (dispatcher != null) {
      dispatcher.close();
    }
    bridges.forEach(ExternalStdioPluginBridge::close);
    if (deadlines != null) {
      deadlines.close();
    }
    if (tasks != null) {
      tasks.close();
    }
  }

  private static List<PluginTapBinding> externalBindings(
      PluginProperties properties,
      ExternalStdioPluginTransportFactory factory,
      List<ExternalStdioPluginBridge> bridges) {
    var bindings = new ArrayList<PluginTapBinding>();
    for (PluginProperties.External external : properties.external()) {
      try {
        var transport = factory.start(external.stdioCommand(), properties.bridgeLimits());
        var bridge =
            ExternalStdioPluginBridge.start(
                transport,
                external.manifest(),
                properties.bridgeLimits(),
                () -> "p-" + UUID.randomUUID());
        bridges.add(bridge);
        bindings.add(new PluginTapBinding(external.manifest(), 0, bridge));
      } catch (IOException | ExternalStdioPluginException failure) {
        throw new IllegalStateException("External Plugin 启动失败", failure);
      }
    }
    return List.copyOf(bindings);
  }

  private static final class RuntimeTaskExecutor implements PluginTaskExecutor, AutoCloseable {
    private final ConcurrentHashMap<RuntimeTask, Boolean> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public PluginTask submit(Runnable task) {
      if (closed.get()) {
        throw new IllegalStateException("Plugin task executor 已关闭");
      }
      var submitted = new RuntimeTask();
      tasks.put(submitted, Boolean.TRUE);
      try {
        Thread worker =
            Thread.ofVirtual()
                .name("namei-plugin-tap")
                .start(
                    () -> {
                      try {
                        if (!submitted.cancelled.get()) {
                          task.run();
                        }
                      } finally {
                        submitted.done.set(true);
                        tasks.remove(submitted);
                      }
                    });
        submitted.worker = worker;
        if (submitted.cancelled.get()) {
          worker.interrupt();
        }
        return submitted;
      } catch (RuntimeException failure) {
        tasks.remove(submitted);
        throw failure;
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        tasks.keySet().forEach(RuntimeTask::cancel);
      }
    }
  }

  private static final class RuntimeTask implements PluginTask {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean done = new AtomicBoolean();
    private volatile Thread worker;

    @Override
    public boolean cancel() {
      boolean changed = cancelled.compareAndSet(false, true);
      Thread current = worker;
      if (current != null) {
        current.interrupt();
      }
      return changed;
    }

    @Override
    public boolean isDone() {
      return done.get();
    }
  }

  private static final class RuntimeDeadlineScheduler
      implements PluginDeadlineScheduler, AutoCloseable {
    private final ScheduledExecutorService scheduler =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofVirtual().name("namei-plugin-deadline", 0).factory());

    @Override
    public PluginDeadline schedule(Duration delay, Runnable task) {
      ScheduledFuture<?> scheduled =
          scheduler.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
      return () -> scheduled.cancel(false);
    }

    @Override
    public void close() {
      scheduler.shutdownNow();
    }
  }
}
