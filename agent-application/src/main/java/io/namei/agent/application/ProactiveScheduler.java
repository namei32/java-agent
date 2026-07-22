package io.namei.agent.application;

import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.port.ProactiveJobStore;
import io.namei.agent.kernel.proactive.ProactiveJobLease;
import io.namei.agent.kernel.proactive.ProactiveJobState;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** 单 JVM 主动循环。它自持 Worker 生命周期，SQLite 自持全部跨重启状态；规划和执行绝不在 Store 事务内发生。 */
public final class ProactiveScheduler implements AutoCloseable {
  private static final String WORKER_NAME = "proactive-scheduler";

  private final ProactiveJobStore store;
  private final ProactiveJobExecutor executor;
  private final ReliableTurnStarter starter;
  private final Clock clock;
  private final ProactiveSchedulerSettings settings;
  private final ProactiveJobObserver observer;
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean started = new AtomicBoolean();
  private final ConcurrentHashMap<String, TurnCancellationSource> active =
      new ConcurrentHashMap<>();
  private final ReentrantLock waitLock = new ReentrantLock();
  private final Condition signalled = waitLock.newCondition();
  private volatile Thread worker;

  public ProactiveScheduler(
      ProactiveJobStore store,
      ProactiveJobExecutor executor,
      ReliableTurnStarter starter,
      Clock clock,
      ProactiveSchedulerSettings settings) {
    this(store, executor, starter, clock, settings, ProactiveJobObserver.noop());
  }

  public ProactiveScheduler(
      ProactiveJobStore store,
      ProactiveJobExecutor executor,
      ReliableTurnStarter starter,
      Clock clock,
      ProactiveSchedulerSettings settings,
      ProactiveJobObserver observer) {
    this.store = Objects.requireNonNull(store, "store");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.starter = Objects.requireNonNull(starter, "starter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.observer = Objects.requireNonNull(observer, "observer");
  }

  public void start() {
    if (!accepting.get()) {
      throw new IllegalStateException("Proactive Scheduler 已关闭");
    }
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("Proactive Scheduler 已启动");
    }
    try {
      worker = Objects.requireNonNull(starter.start(WORKER_NAME, this::run), "starter 返回了 null");
    } catch (RuntimeException | Error failure) {
      started.set(false);
      throw failure;
    }
  }

  /** 最多执行一个 Job；为支持确定性的健康检查和测试而有意公开。 */
  public ProactiveSchedulerStep tick() {
    if (!accepting.get()) {
      return ProactiveSchedulerStep.CLOSED;
    }
    Instant now = clock.instant();
    store.recoverExpired(now, settings.recoveryBatchSize());
    var claim = store.claimNext(now, settings.ownerId(), settings.leaseDuration());
    if (claim.isEmpty()) {
      return ProactiveSchedulerStep.IDLE;
    }
    var running = store.markRunning(claim.orElseThrow(), now);
    if (running.isEmpty()) {
      return ProactiveSchedulerStep.LEASE_LOST;
    }
    return execute(running.orElseThrow());
  }

  public boolean cancel(String jobRef) {
    if (jobRef == null) {
      return false;
    }
    TurnCancellationSource source = active.get(jobRef);
    return source != null && source.cancel();
  }

  public boolean isRunning() {
    Thread current = worker;
    return current != null && current.isAlive();
  }

  @Override
  public void close() {
    if (!accepting.compareAndSet(true, false)) {
      return;
    }
    active.forEach((ignored, cancellation) -> cancellation.cancel(TurnCancellationCode.SHUTDOWN));
    signal();
    Thread current = worker;
    if (current == null || current == Thread.currentThread()) {
      return;
    }
    try {
      current.join(Math.max(1, settings.shutdownTimeout().toMillis()));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
    }
  }

  private ProactiveSchedulerStep execute(ProactiveJobLease lease) {
    TurnCancellationSource cancellation = new TurnCancellationSource();
    String key = lease.job().jobRef().value();
    if (active.putIfAbsent(key, cancellation) != null) {
      return finish(lease, ProactiveJobState.SKIPPED);
    }
    try {
      if (!accepting.get()) {
        cancellation.cancel();
      }
      ProactiveJobState terminal;
      try {
        terminal = executor.execute(lease, cancellation.token());
      } catch (RuntimeException failure) {
        terminal = ProactiveJobState.FAILED;
      }
      if (cancellation.token().isCancellationRequested()) {
        terminal = ProactiveJobState.CANCELLED;
      }
      if (terminal == null || !terminal.terminal()) {
        terminal = ProactiveJobState.FAILED;
      }
      return finish(lease, terminal);
    } finally {
      active.remove(key, cancellation);
    }
  }

  private ProactiveSchedulerStep finish(ProactiveJobLease lease, ProactiveJobState terminal) {
    boolean committed = store.complete(lease, terminal, clock.instant());
    if (!committed) {
      return ProactiveSchedulerStep.LEASE_LOST;
    }
    try {
      observer.onCommitted(lease, terminal);
    } catch (RuntimeException ignored) {
      // 可观察性发生在提交后，不能改变持久状态。
    }
    return terminal == ProactiveJobState.CANCELLED
        ? ProactiveSchedulerStep.CANCELLED
        : terminal == ProactiveJobState.FAILED
            ? ProactiveSchedulerStep.FAILED
            : ProactiveSchedulerStep.COMPLETED;
  }

  private void run() {
    while (accepting.get() && !Thread.currentThread().isInterrupted()) {
      ProactiveSchedulerStep step;
      try {
        step = tick();
      } catch (RuntimeException ignored) {
        step = ProactiveSchedulerStep.FAILED;
      }
      if (step == ProactiveSchedulerStep.COMPLETED) {
        continue;
      }
      awaitSignal();
    }
  }

  private void signal() {
    waitLock.lock();
    try {
      signalled.signalAll();
    } finally {
      waitLock.unlock();
    }
  }

  private void awaitSignal() {
    try {
      waitLock.lockInterruptibly();
      try {
        if (accepting.get()) {
          signalled.awaitNanos(settings.idleWait().toNanos());
        }
      } finally {
        waitLock.unlock();
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
    }
  }
}
