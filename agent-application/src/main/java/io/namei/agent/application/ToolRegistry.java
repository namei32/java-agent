package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ToolRegistry {
  private final ToolCatalog catalog;
  private final java.util.Map<String, ToolSchemaValidator> validators;
  private final ToolRuntimeSettings settings;
  private final Semaphore executionPermits;
  private final ToolTaskStarter taskStarter;

  ToolRegistry(List<Tool> tools) {
    this(tools, ToolRuntimeSettings.readOnlyDefaults());
  }

  ToolRegistry(List<Tool> tools, ToolRuntimeSettings settings) {
    this(
        ToolCatalog.alwaysOn(tools),
        settings,
        (toolName, task) -> Thread.ofVirtual().name("namei-tool-" + toolName).start(task));
  }

  ToolRegistry(ToolCatalog catalog, ToolRuntimeSettings settings) {
    this(
        catalog,
        settings,
        (toolName, task) -> Thread.ofVirtual().name("namei-tool-" + toolName).start(task));
  }

  ToolRegistry(List<Tool> tools, ToolRuntimeSettings settings, ToolTaskStarter taskStarter) {
    this(ToolCatalog.alwaysOn(tools), settings, taskStarter);
  }

  ToolRegistry(ToolCatalog catalog, ToolRuntimeSettings settings, ToolTaskStarter taskStarter) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.taskStarter = Objects.requireNonNull(taskStarter, "taskStarter");
    this.executionPermits = new Semaphore(settings.maxConcurrentCalls(), true);
    if (settings.mode() == ToolRuntimeMode.DISABLED) {
      this.validators = java.util.Map.of();
      return;
    }
    var registeredValidators = new java.util.LinkedHashMap<String, ToolSchemaValidator>();
    for (ToolDefinition definition : catalog.allDefinitions()) {
      if (settings.mode() == ToolRuntimeMode.READ_ONLY && definition.risk() != ToolRisk.READ_ONLY) {
        throw new IllegalArgumentException("READ_ONLY 模式不能注册副作用工具");
      }
      registeredValidators.put(
          definition.name(), new ToolSchemaValidator(definition.inputSchema()));
    }
    this.validators = java.util.Map.copyOf(registeredValidators);
  }

  List<Optional<ToolResult>> preflight(List<ToolCall> calls) {
    return prepare(calls, newCatalogSession()).stream()
        .map(item -> Optional.ofNullable(item.preflightFailure()))
        .toList();
  }

  List<PreparedCall> prepare(List<ToolCall> calls) {
    return prepare(calls, newCatalogSession());
  }

  List<PreparedCall> prepare(List<ToolCall> calls, ToolCatalogSession session) {
    Objects.requireNonNull(calls, "calls");
    Objects.requireNonNull(session, "session");
    if (settings.mode() == ToolRuntimeMode.DISABLED) {
      return calls.stream()
          .map(call -> new PreparedCall(call, null, ToolResult.error("工具不可用。")))
          .toList();
    }
    return calls.stream()
        .map(
            call -> {
              Objects.requireNonNull(call, "call");
              var definition =
                  catalog.isVisible(session, call.name()) ? catalog.definition(call.name()) : null;
              var validator = validators.get(call.name());
              ToolResult failure =
                  definition == null
                      ? ToolResult.error("工具不可用。")
                      : !validator.accepts(call.arguments()) ? ToolResult.error("工具参数无效。") : null;
              return new PreparedCall(call, definition, failure);
            })
        .toList();
  }

  List<ToolDefinition> definitions() {
    return definitions(newCatalogSession());
  }

  List<ToolDefinition> definitions(ToolCatalogSession session) {
    if (settings.mode() == ToolRuntimeMode.DISABLED) {
      return List.of();
    }
    return catalog.definitions(session);
  }

  ToolCatalogSession newCatalogSession() {
    return catalog.newSession();
  }

  ToolResult execute(ToolCall call) {
    return execute(call, TurnCancellation.none());
  }

  ToolResult execute(ToolCall call, TurnCancellation cancellation) {
    return execute(call, cancellation, newCatalogSession());
  }

  ToolResult execute(ToolCall call, TurnCancellation cancellation, ToolCatalogSession session) {
    return execute(call, cancellation, session, ToolInvocationContext.none());
  }

  ToolResult execute(
      ToolCall call,
      TurnCancellation cancellation,
      ToolCatalogSession session,
      ToolInvocationContext invocationContext) {
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(invocationContext, "invocationContext");
    if (settings.mode() == ToolRuntimeMode.DISABLED) {
      return ToolResult.error("工具不可用。");
    }
    if (!catalog.isVisible(session, call.name())) {
      return ToolResult.error("工具不可用。");
    }
    if (catalog.isSearchTool(call.name())) {
      ToolSchemaValidator validator = validators.get(call.name());
      if (validator == null || !validator.accepts(call.arguments())) {
        return ToolResult.error("工具参数无效。");
      }
      return search(call, session);
    }
    var tool = catalog.tool(call.name());
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

    var task = new FutureTask<ToolResult>(() -> invoke(tool, call, invocationContext));
    Runnable worker =
        () -> {
          try {
            task.run();
          } finally {
            executionPermits.release();
          }
        };
    try {
      taskStarter.start(call.name(), worker);
    } catch (RuntimeException exception) {
      executionPermits.release();
      return ToolResult.error("工具执行失败。");
    } catch (Error error) {
      executionPermits.release();
      throw error;
    }
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

  private ToolResult search(ToolCall call, ToolCatalogSession session) {
    Object rawQuery = call.arguments().get("query");
    Object rawTopK = call.arguments().get("top_k");
    if (!(rawQuery instanceof String query)) {
      return ToolResult.error("工具参数无效。");
    }
    int topK = 5;
    if (rawTopK != null) {
      if (!(rawTopK instanceof Number number)) {
        return ToolResult.error("工具参数无效。");
      }
      topK = number.intValue();
      if (number.longValue() != topK) {
        return ToolResult.error("工具参数无效。");
      }
    }
    try {
      return ToolResult.success(ToolCatalogJson.render(catalog.search(session, query, topK)));
    } catch (IllegalArgumentException exception) {
      return ToolResult.error("工具参数无效。");
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

  private ToolResult invoke(Tool tool, ToolCall call, ToolInvocationContext invocationContext) {
    try {
      var result =
          tool instanceof ContextualTool contextual
              ? contextual.execute(call.arguments(), invocationContext)
              : tool.execute(call.arguments());
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

  @FunctionalInterface
  interface ToolTaskStarter {
    void start(String toolName, Runnable task);
  }

  record PreparedCall(ToolCall call, ToolDefinition definition, ToolResult preflightFailure) {}
}
