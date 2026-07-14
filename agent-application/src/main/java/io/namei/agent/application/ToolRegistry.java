package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ToolRegistry {
  private final Map<String, Tool> tools;
  private final Map<String, ToolSchemaValidator> validators;
  private final List<ToolDefinition> definitions;
  private final ToolRuntimeSettings settings;
  private final Semaphore executionPermits;

  ToolRegistry(List<Tool> tools) {
    this(tools, ToolRuntimeSettings.readOnlyDefaults());
  }

  ToolRegistry(List<Tool> tools, ToolRuntimeSettings settings) {
    Objects.requireNonNull(tools, "tools");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.executionPermits = new Semaphore(settings.maxConcurrentCalls(), true);
    if (settings.mode() == ToolRuntimeMode.DISABLED) {
      this.tools = Map.of();
      this.validators = Map.of();
      this.definitions = List.of();
      return;
    }
    var registered = new LinkedHashMap<String, Tool>();
    var registeredValidators = new LinkedHashMap<String, ToolSchemaValidator>();
    for (Tool tool : tools) {
      Objects.requireNonNull(tool, "tool");
      var definition = Objects.requireNonNull(tool.definition(), "tool.definition");
      if (registered.putIfAbsent(definition.name(), tool) != null) {
        throw new IllegalArgumentException("工具名称重复: " + definition.name());
      }
      registeredValidators.put(
          definition.name(), new ToolSchemaValidator(definition.inputSchema()));
    }
    this.tools = Map.copyOf(registered);
    this.validators = Map.copyOf(registeredValidators);
    this.definitions = registered.values().stream().map(Tool::definition).toList();
  }

  List<Optional<ToolResult>> preflight(List<ToolCall> calls) {
    return calls.stream()
        .map(
            call -> {
              var validator = validators.get(call.name());
              if (validator != null && !validator.accepts(call.arguments())) {
                return Optional.of(ToolResult.error("工具参数无效。"));
              }
              return Optional.<ToolResult>empty();
            })
        .toList();
  }

  List<ToolDefinition> definitions() {
    return definitions;
  }

  ToolResult execute(ToolCall call) {
    return execute(call, TurnCancellation.none());
  }

  ToolResult execute(ToolCall call, TurnCancellation cancellation) {
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(cancellation, "cancellation");
    var tool = tools.get(call.name());
    if (tool == null) {
      return ToolResult.error("工具不可用。");
    }
    if (cancellation.isCancellationRequested()) {
      return ToolResult.cancelled();
    }

    long startedAt = System.nanoTime();
    long timeoutNanos = timeoutNanos();
    if (!acquirePermit(cancellation, startedAt, timeoutNanos)) {
      return cancellation.isCancellationRequested() ? ToolResult.cancelled() : ToolResult.timeout();
    }
    if (cancellation.isCancellationRequested()) {
      Thread.interrupted();
      executionPermits.release();
      return ToolResult.cancelled();
    }
    if (remainingNanos(startedAt, timeoutNanos) <= 0) {
      executionPermits.release();
      return ToolResult.timeout();
    }

    var task =
        new FutureTask<ToolResult>(
            () -> {
              try {
                return invoke(tool, call);
              } finally {
                executionPermits.release();
              }
            });
    Thread.ofVirtual().name("namei-tool-" + call.name()).start(task);
    try (var registration = cancellation.onCancellation(() -> task.cancel(true))) {
      long remaining = remainingNanos(startedAt, timeoutNanos);
      if (remaining <= 0) {
        task.cancel(true);
        return ToolResult.timeout();
      }
      var result = task.get(remaining, TimeUnit.NANOSECONDS);
      return cancellation.isCancellationRequested() ? ToolResult.cancelled() : result;
    } catch (TimeoutException exception) {
      task.cancel(true);
      return ToolResult.timeout();
    } catch (CancellationException exception) {
      return cancellation.isCancellationRequested()
          ? ToolResult.cancelled()
          : ToolResult.error("工具执行失败。");
    } catch (InterruptedException exception) {
      task.cancel(true);
      if (cancellation.isCancellationRequested()) {
        return ToolResult.cancelled();
      }
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待工具执行时被中断", exception);
    } catch (ExecutionException exception) {
      return ToolResult.error("工具执行失败。");
    }
  }

  private boolean acquirePermit(TurnCancellation cancellation, long startedAt, long timeoutNanos) {
    long remaining = remainingNanos(startedAt, timeoutNanos);
    if (remaining <= 0) {
      return false;
    }
    Thread waiter = Thread.currentThread();
    try (var registration = cancellation.onCancellation(waiter::interrupt)) {
      try {
        return executionPermits.tryAcquire(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException exception) {
        if (cancellation.isCancellationRequested()) {
          return false;
        }
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待工具执行许可时被中断", exception);
      }
    }
  }

  private ToolResult invoke(Tool tool, ToolCall call) {
    try {
      var result = tool.execute(call.arguments());
      if (result == null) {
        return ToolResult.error("工具执行失败。");
      }
      if (result.content().codePointCount(0, result.content().length())
          > settings.maxResultCharacters()) {
        return ToolResult.error("工具结果超过大小限制。");
      }
      return result;
    } catch (RuntimeException ignored) {
      return ToolResult.error("工具执行失败。");
    }
  }

  private long timeoutNanos() {
    try {
      return settings.timeout().toNanos();
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
  }

  private static long remainingNanos(long startedAt, long timeoutNanos) {
    return timeoutNanos - (System.nanoTime() - startedAt);
  }
}
