package io.namei.agent.application.plugin;

import io.namei.agent.kernel.plugin.PluginCatalog;
import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginStableCode;
import io.namei.agent.kernel.plugin.PluginTapEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PluginTapDispatcher implements AutoCloseable {
  private final List<Entry> entries;
  private final Duration timeout;
  private final PluginTaskExecutor executor;
  private final PluginDeadlineScheduler deadlines;
  private final PluginTapAudit audit;
  private final AtomicBoolean accepting = new AtomicBoolean(true);

  public PluginTapDispatcher(
      List<PluginTapBinding> bindings,
      Duration timeout,
      PluginTaskExecutor executor,
      PluginDeadlineScheduler deadlines,
      PluginTapAudit audit) {
    Objects.requireNonNull(bindings, "bindings");
    PluginCatalog.of(bindings.stream().map(PluginTapBinding::manifest).toList());
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Plugin Tap timeout 必须为正数");
    }
    this.timeout = timeout;
    this.executor = Objects.requireNonNull(executor, "executor");
    this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.entries =
        bindings.stream()
            .map(Entry::new)
            .sorted(
                Comparator.comparingInt((Entry entry) -> entry.binding.priority())
                    .reversed()
                    .thenComparing(entry -> entry.binding.manifest().id()))
            .toList();
  }

  public void publish(PluginTapEvent event) {
    Objects.requireNonNull(event, "event");
    if (!accepting.get()) {
      return;
    }
    for (Entry entry : entries) {
      dispatch(entry, event);
    }
  }

  public List<PluginTapStatus> snapshot() {
    return entries.stream().map(Entry::snapshot).toList();
  }

  @Override
  public void close() {
    if (!accepting.compareAndSet(true, false)) {
      return;
    }
    for (Entry entry : entries) {
      entry.stop();
      audit(entry, PluginTapAuditAction.TAP_STOPPED, Optional.empty(), 0);
    }
  }

  private void dispatch(Entry entry, PluginTapEvent event) {
    if (!entry.supports(event) || entry.state.get() != PluginRuntimeState.ACTIVE) {
      return;
    }
    if (!entry.inFlight.compareAndSet(false, true)) {
      long dropped = entry.droppedEvents.incrementAndGet();
      audit(entry, PluginTapAuditAction.TAP_DROPPED_BUSY, Optional.empty(), dropped);
      return;
    }
    try {
      PluginTask submitted = executor.submit(() -> run(entry, event));
      entry.task.set(Objects.requireNonNull(submitted, "PluginTaskExecutor 返回 null"));
      PluginDeadline deadline = deadlines.schedule(timeout, () -> timeout(entry));
      entry.deadline.set(Objects.requireNonNull(deadline, "PluginDeadlineScheduler 返回 null"));
      if (!entry.inFlight.get()) {
        deadline.cancel();
      }
      audit(entry, PluginTapAuditAction.TAP_SUBMITTED, Optional.empty(), 1);
    } catch (RuntimeException rejected) {
      fail(entry, PluginStableCode.PLUGIN_EXECUTION_FAILED);
    }
  }

  private void run(Entry entry, PluginTapEvent event) {
    try {
      if (entry.state.get() == PluginRuntimeState.ACTIVE) {
        entry.binding.tap().accept(event);
      }
    } catch (Exception failure) {
      fail(entry, PluginStableCode.PLUGIN_EXECUTION_FAILED);
    } finally {
      complete(entry);
    }
  }

  private void timeout(Entry entry) {
    if (!entry.inFlight.compareAndSet(true, false)) {
      return;
    }
    PluginTask task = entry.task.get();
    if (task != null) {
      task.cancel();
    }
    fail(entry, PluginStableCode.PLUGIN_TIMEOUT);
  }

  private void complete(Entry entry) {
    entry.inFlight.set(false);
    PluginDeadline deadline = entry.deadline.getAndSet(null);
    if (deadline != null) {
      deadline.cancel();
    }
  }

  private void fail(Entry entry, PluginStableCode code) {
    entry.state.set(PluginRuntimeState.FAILED);
    entry.lastCode.set(code);
    entry.inFlight.set(false);
    PluginDeadline deadline = entry.deadline.getAndSet(null);
    if (deadline != null) {
      deadline.cancel();
    }
    audit(
        entry,
        code == PluginStableCode.PLUGIN_TIMEOUT
            ? PluginTapAuditAction.TAP_TIMEOUT
            : PluginTapAuditAction.TAP_FAILED,
        Optional.of(code),
        1);
  }

  private void audit(
      Entry entry, PluginTapAuditAction action, Optional<PluginStableCode> code, long count) {
    try {
      audit.record(
          new PluginTapAuditEvent(hash(entry.binding.manifest().id()), action, code, count));
    } catch (RuntimeException ignored) {
      // 审计失败不能改变主 Agent 或 Plugin 隔离结果。
    }
  }

  private static String hash(PluginId id) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(("plugin-tap-v1:" + id.value()).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 不可用", unavailable);
    }
  }

  private static final class Entry {
    private final PluginTapBinding binding;
    private final AtomicReference<PluginRuntimeState> state =
        new AtomicReference<>(PluginRuntimeState.ACTIVE);
    private final AtomicReference<PluginStableCode> lastCode = new AtomicReference<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicReference<PluginTask> task = new AtomicReference<>();
    private final AtomicReference<PluginDeadline> deadline = new AtomicReference<>();

    private Entry(PluginTapBinding binding) {
      this.binding = binding;
    }

    private boolean supports(PluginTapEvent event) {
      return binding.manifest().capabilities().contains(event.capability());
    }

    private PluginTapStatus snapshot() {
      return new PluginTapStatus(
          binding.manifest().id(),
          state.get(),
          Optional.ofNullable(lastCode.get()),
          droppedEvents.get());
    }

    private void stop() {
      state.set(PluginRuntimeState.STOPPING);
      PluginTask currentTask = task.getAndSet(null);
      if (currentTask != null) {
        currentTask.cancel();
      }
      PluginDeadline currentDeadline = deadline.getAndSet(null);
      if (currentDeadline != null) {
        currentDeadline.cancel();
      }
      inFlight.set(false);
      state.set(PluginRuntimeState.STOPPED);
    }
  }
}
