package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelContextLimitException;
import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.model.ModelMessage;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 在模型响应与工具结果之间迭代的核心编排器。
 *
 * <p>每轮把当前消息发送给模型；若模型返回 Tool Call，则经注册表、Schema、风险策略、审批和副作用协调器执行，再把结果作为 Tool 消息加入上下文。达到迭代或调用
 * 上限、发生取消或模型返回非法响应时立即失败。
 */
final class ToolLoop {
  private final ChatModelPort model;
  private final ToolRegistry tools;
  private final LifecyclePublisher lifecycle;
  private final int maxIterations;
  private final ToolRuntimeSettings settings;
  private final SideEffectBatchCoordinator coordinator;
  private final ModelStreamingSettings streamingSettings;
  private final MemoryForgetPendingProducer pendingProducer;

  ToolLoop(
      ChatModelPort model, ToolRegistry tools, LifecyclePublisher lifecycle, int maxIterations) {
    this(model, tools, lifecycle, maxIterations, ToolRuntimeSettings.readOnlyDefaults());
  }

  ToolLoop(
      ChatModelPort model,
      ToolRegistry tools,
      LifecyclePublisher lifecycle,
      int maxIterations,
      ToolRuntimeSettings settings) {
    this(
        model,
        tools,
        lifecycle,
        maxIterations,
        settings,
        new SideEffectBatchCoordinator(
            tools,
            request -> null,
            ToolExecutionPolicy.registeredRisk(),
            Clock.systemUTC(),
            Duration.ofMinutes(5),
            new SecureIdGenerator(),
            SideEffectLedger.unavailable(),
            lifecycle));
  }

  ToolLoop(
      ChatModelPort model,
      ToolRegistry tools,
      LifecyclePublisher lifecycle,
      int maxIterations,
      ToolRuntimeSettings settings,
      SideEffectBatchCoordinator coordinator) {
    this(
        model,
        tools,
        lifecycle,
        maxIterations,
        settings,
        coordinator,
        ModelStreamingSettings.defaults(),
        MemoryForgetPendingToolset.disabled());
  }

  ToolLoop(
      ChatModelPort model,
      ToolRegistry tools,
      LifecyclePublisher lifecycle,
      int maxIterations,
      ToolRuntimeSettings settings,
      SideEffectBatchCoordinator coordinator,
      ModelStreamingSettings streamingSettings) {
    this(
        model,
        tools,
        lifecycle,
        maxIterations,
        settings,
        coordinator,
        streamingSettings,
        MemoryForgetPendingToolset.disabled());
  }

  ToolLoop(
      ChatModelPort model,
      ToolRegistry tools,
      LifecyclePublisher lifecycle,
      int maxIterations,
      ToolRuntimeSettings settings,
      SideEffectBatchCoordinator coordinator,
      ModelStreamingSettings streamingSettings,
      MemoryForgetPendingToolset pendingToolset) {
    this.model = Objects.requireNonNull(model, "model");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    if (maxIterations < 1) {
      throw new IllegalArgumentException("Tool Loop 最大迭代次数必须大于零");
    }
    this.maxIterations = maxIterations;
    this.settings = Objects.requireNonNull(settings, "settings");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.streamingSettings = Objects.requireNonNull(streamingSettings, "streamingSettings");
    this.pendingProducer = Objects.requireNonNull(pendingToolset, "pendingToolset").producer();
  }

  String complete(List<? extends ModelMessage> initialMessages) {
    return complete(initialMessages, TurnCancellation.none());
  }

  String complete(List<? extends ModelMessage> initialMessages, TurnCancellation cancellation) {
    return complete(
        initialMessages,
        cancellation,
        new SideEffectBatchCoordinator.Context(
            "local-session-binding", new SecureIdGenerator().newTurnId()));
  }

  String complete(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context) {
    return finalContent(
        completeResult(
            initialMessages,
            cancellation,
            context,
            ToolInvocationContext.none(),
            null,
            null,
            null));
  }

  String complete(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext) {
    return finalContent(
        completeResult(
            initialMessages, cancellation, context, invocationContext, null, null, null));
  }

  String complete(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext,
      ProviderTurnUsageCollector usageCollector) {
    return finalContent(
        completeResult(
            initialMessages, cancellation, context, invocationContext, null, usageCollector, null));
  }

  String completeStreaming(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ChatProgressListener progressListener) {
    return completeStreaming(
        initialMessages, cancellation, context, ToolInvocationContext.none(), progressListener);
  }

  String completeStreaming(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext,
      ChatProgressListener progressListener) {
    return finalContent(
        completeResult(
            initialMessages,
            cancellation,
            context,
            invocationContext,
            Objects.requireNonNull(progressListener, "progressListener"),
            null,
            null));
  }

  String completeStreaming(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext,
      ChatProgressListener progressListener,
      ProviderTurnUsageCollector usageCollector) {
    return finalContent(
        completeResult(
            initialMessages,
            cancellation,
            context,
            invocationContext,
            Objects.requireNonNull(progressListener, "progressListener"),
            usageCollector,
            null));
  }

  ToolLoopCompletion completeForPendingTurn(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext,
      MemoryForgetPendingTurnContext pendingTurnContext) {
    return completeForPendingTurn(
        initialMessages, cancellation, context, invocationContext, pendingTurnContext, null);
  }

  ToolLoopCompletion completeForPendingTurn(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext,
      MemoryForgetPendingTurnContext pendingTurnContext,
      ProviderTurnUsageCollector usageCollector) {
    return completeResult(
        initialMessages,
        cancellation,
        context,
        invocationContext,
        null,
        usageCollector,
        Objects.requireNonNull(pendingTurnContext, "pendingTurnContext"));
  }

  ToolLoopCompletion completeStreamingForPendingTurn(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext,
      MemoryForgetPendingTurnContext pendingTurnContext,
      ChatProgressListener progressListener,
      ProviderTurnUsageCollector usageCollector) {
    return completeResult(
        initialMessages,
        cancellation,
        context,
        invocationContext,
        Objects.requireNonNull(progressListener, "progressListener"),
        usageCollector,
        Objects.requireNonNull(pendingTurnContext, "pendingTurnContext"));
  }

  boolean pendingProducerEnabled() {
    return pendingProducer.isEnabled();
  }

  private ToolLoopCompletion completeResult(
      List<? extends ModelMessage> initialMessages,
      TurnCancellation cancellation,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext,
      ChatProgressListener progressListener,
      ProviderTurnUsageCollector usageCollector,
      MemoryForgetPendingTurnContext pendingTurnContext) {
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(invocationContext, "invocationContext");
    var messages = new ArrayList<ModelMessage>(initialMessages);
    ToolCatalogSession catalogSession = tools.newCatalogSession();
    var streamingBudget = progressListener == null ? null : new StreamingBudget(streamingSettings);
    int totalCalls = 0;
    boolean toolExecuted = false;
    for (int iteration = 1; iteration <= maxIterations; iteration++) {
      checkCancellation(cancellation);
      lifecycle.emit(TurnLifecycleEvent.modelRequested(iteration));
      var request = new ChatModelRequest(messages, tools.definitions(catalogSession));
      var response =
          generate(request, cancellation, progressListener, streamingBudget, toolExecuted);
      checkCancellation(cancellation);
      if (response == null) {
        lifecycle.emit(TurnLifecycleEvent.modelCompleted(iteration, "INVALID"));
        throw new InvalidModelResponseException("模型返回了无效响应");
      }
      if (usageCollector != null) {
        usageCollector.accept(response);
      }
      if (!response.hasToolCalls()) {
        lifecycle.emit(TurnLifecycleEvent.modelCompleted(iteration, "FINAL"));
        return new ToolLoopCompletion.Final(response.content());
      }

      if (settings.mode() == ToolRuntimeMode.DISABLED) {
        lifecycle.emit(TurnLifecycleEvent.modelCompleted(iteration, "INVALID"));
        throw new InvalidModelResponseException("禁用工具时模型返回了 Tool Call");
      }

      lifecycle.emit(TurnLifecycleEvent.modelCompleted(iteration, "TOOL_CALLS"));
      int callsInResponse = response.toolCalls().size();
      if (callsInResponse > settings.maxCallsPerResponse()
          || (long) totalCalls + callsInResponse > settings.maxCallsPerTurn()) {
        throw new ToolCallLimitExceededException("Tool Call 超过安全上限");
      }
      totalCalls += callsInResponse;
      messages.add(
          new AssistantToolCallMessage(
              response.content(), response.toolCalls(), response.reasoning().orElse(null)));
      if (containsMemoryForget(response.toolCalls())) {
        ToolLoopCompletion pending =
            createPendingIfRequested(
                iteration,
                response.toolCalls(),
                cancellation,
                catalogSession,
                context,
                pendingTurnContext,
                messages);
        if (pending != null) {
          return pending;
        }
        toolExecuted = true;
        continue;
      }
      var results =
          coordinator.execute(
              context,
              iteration,
              response.toolCalls(),
              cancellation,
              catalogSession,
              invocationContext);
      toolExecuted = true;
      for (int callIndex = 0; callIndex < response.toolCalls().size(); callIndex++) {
        var call = response.toolCalls().get(callIndex);
        var result = results.get(callIndex);
        if (cancellation.isCancellationRequested()) {
          result = ToolResult.cancelled();
        }
        if (result.status() == ToolResultStatus.CANCELLED) {
          throw new TurnCancelledException("当前 Turn 已取消");
        }
        messages.add(new ToolResultMessage(call, result));
      }
    }
    throw new ToolLoopLimitExceededException("Tool Loop 超过最大迭代次数");
  }

  private ToolLoopCompletion createPendingIfRequested(
      int iteration,
      List<io.namei.agent.kernel.tool.ToolCall> calls,
      TurnCancellation cancellation,
      ToolCatalogSession catalogSession,
      SideEffectBatchCoordinator.Context context,
      MemoryForgetPendingTurnContext pendingTurnContext,
      List<ModelMessage> messages) {
    if (!pendingProducer.isEnabled()
        || pendingTurnContext == null
        || calls.size() != 1
        || !context.turnId().equals(pendingTurnContext.turnId())) {
      throw new InvalidModelResponseException("Memory Forget Pending Tool Call 不满足受限创建条件");
    }
    var call = calls.getFirst();
    var prepared = tools.prepare(List.of(call), catalogSession).getFirst();
    if (prepared.preflightFailure() != null
        || prepared.definition() == null
        || prepared.definition().risk() != ToolRisk.WRITE) {
      throw new InvalidModelResponseException("Memory Forget Pending Tool Call 无效或不可见");
    }
    lifecycle.emit(TurnLifecycleEvent.toolStarted(iteration, call.id(), call.name()));
    checkCancellation(cancellation);
    boolean persistsPending =
        !MemoryForgetCapability.normalizeArguments(call.arguments()).isEmpty();
    if (persistsPending) {
      lifecycle.emit(TurnLifecycleEvent.turnCommitting());
    }
    MemoryForgetPendingOutcome outcome;
    try {
      outcome = pendingProducer.create(pendingTurnContext, call);
    } catch (RuntimeException failure) {
      lifecycle.emit(
          TurnLifecycleEvent.toolCompleted(
              iteration, call.id(), call.name(), ToolResultStatus.ERROR));
      throw failure;
    }
    if (outcome instanceof MemoryForgetPendingOutcome.ImmediateSuccess immediate) {
      lifecycle.emit(
          TurnLifecycleEvent.toolCompleted(
              iteration, call.id(), call.name(), ToolResultStatus.SUCCESS));
      messages.add(new ToolResultMessage(call, immediate.safeResult()));
      return null;
    }
    if (outcome instanceof MemoryForgetPendingOutcome.Pending) {
      lifecycle.emit(
          TurnLifecycleEvent.toolCompleted(
              iteration, call.id(), call.name(), ToolResultStatus.SUCCESS));
      return new ToolLoopCompletion.Pending(
          MemoryForgetPendingToolset.pendingAssistantProjection());
    }
    lifecycle.emit(
        TurnLifecycleEvent.toolCompleted(
            iteration, call.id(), call.name(), ToolResultStatus.ERROR));
    throw new IllegalStateException("Memory Forget Pending Anchor 未能提交");
  }

  private static boolean containsMemoryForget(List<io.namei.agent.kernel.tool.ToolCall> calls) {
    return calls.stream().anyMatch(call -> MemoryForgetCapability.TOOL_NAME.equals(call.name()));
  }

  private static String finalContent(ToolLoopCompletion completion) {
    if (completion instanceof ToolLoopCompletion.Final finalCompletion) {
      return finalCompletion.content();
    }
    throw new IllegalStateException("普通 Tool Loop 不能提交 Pending Turn");
  }

  private ChatModelResponse generate(
      ChatModelRequest request,
      TurnCancellation cancellation,
      ChatProgressListener progressListener,
      StreamingBudget streamingBudget,
      boolean toolExecuted) {
    try {
      return progressListener == null
          ? model.generate(request)
          : model.generate(
              request,
              delta -> {
                checkCancellation(cancellation);
                streamingBudget.accept(delta);
                progressListener.onContentDelta(delta);
              },
              cancellation);
    } catch (ModelContextLimitException failure) {
      if (progressListener == null && !toolExecuted) {
        throw new ContextLimitRecoveryCandidateException(failure);
      }
      throw failure;
    }
  }

  private static void checkCancellation(TurnCancellation cancellation) {
    if (cancellation.isCancellationRequested()) {
      throw new TurnCancelledException("当前 Turn 已取消");
    }
  }
}
