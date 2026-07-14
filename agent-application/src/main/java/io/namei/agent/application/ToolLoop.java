package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.AssistantToolCallMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ModelMessage;
import io.namei.agent.kernel.model.ToolResultMessage;
import io.namei.agent.kernel.port.ChatModelPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ToolLoop {
  private final ChatModelPort model;
  private final ToolRegistry tools;
  private final LifecyclePublisher lifecycle;
  private final int maxIterations;
  private final ToolRuntimeSettings settings;

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
    this.model = Objects.requireNonNull(model, "model");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    if (maxIterations < 1) {
      throw new IllegalArgumentException("Tool Loop 最大迭代次数必须大于零");
    }
    this.maxIterations = maxIterations;
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  String complete(List<? extends ModelMessage> initialMessages) {
    var messages = new ArrayList<ModelMessage>(initialMessages);
    for (int iteration = 1; iteration <= maxIterations; iteration++) {
      lifecycle.emit(TurnLifecycleEvent.modelRequested(iteration));
      var response = model.generate(new ChatModelRequest(messages, tools.definitions()));
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
      messages.add(new AssistantToolCallMessage(response.content(), response.toolCalls()));
      for (var call : response.toolCalls()) {
        lifecycle.emit(TurnLifecycleEvent.toolStarted(iteration, call.id(), call.name()));
        var result = tools.execute(call);
        messages.add(new ToolResultMessage(call, result));
        lifecycle.emit(
            TurnLifecycleEvent.toolCompleted(iteration, call.id(), call.name(), result.status()));
      }
    }
    throw new ToolLoopLimitExceededException("Tool Loop 超过最大迭代次数");
  }
}
