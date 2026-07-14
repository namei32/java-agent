package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ModelMessage;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.time.Clock;
import java.time.Duration;

final class ToolLoop {
  private final ChatModelPort model;
  private final ToolRegistry tools;
  private final LifecyclePublisher lifecycle;
  private final int maxIterations;
  private final ToolRuntimeSettings settings;
  private final SideEffectBatchCoordinator coordinator;

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
    this.model = Objects.requireNonNull(model, "model");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    if (maxIterations < 1) {
      throw new IllegalArgumentException("Tool Loop 最大迭代次数必须大于零");
    }
    this.maxIterations = maxIterations;
    this.settings = Objects.requireNonNull(settings, "settings");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
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
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(context, "context");
    var messages = new ArrayList<ModelMessage>(initialMessages);
    int totalCalls = 0;
    for (int iteration = 1; iteration <= maxIterations; iteration++) {
      checkCancellation(cancellation);
      lifecycle.emit(TurnLifecycleEvent.modelRequested(iteration));
      var response = model.generate(new ChatModelRequest(messages, tools.definitions()));
      checkCancellation(cancellation);
      if (response == null) {
        lifecycle.emit(TurnLifecycleEvent.modelCompleted(iteration, "INVALID"));
        throw new InvalidModelResponseException("模型返回了无效响应");
      }
      if (!response.hasToolCalls()) {
        lifecycle.emit(TurnLifecycleEvent.modelCompleted(iteration, "FINAL"));
        return response.content();
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
      messages.add(new AssistantToolCallMessage(response.content(), response.toolCalls()));
      var results = coordinator.execute(context, iteration, response.toolCalls(), cancellation);
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

  private static void checkCancellation(TurnCancellation cancellation) {
    if (cancellation.isCancellationRequested()) {
      throw new TurnCancelledException("当前 Turn 已取消");
    }
  }
}
