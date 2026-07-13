package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ModelMessage;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChatService implements ChatUseCase {
  private final SessionRepository sessions;
  private final ConversationHistorySelector historySelector;
  private final HistoryLimits limits;
  private final SessionExecutionGate gate;
  private final String systemPrompt;
  private final Clock clock;
  private final ToolLoop toolLoop;
  private final LifecyclePublisher lifecycle;

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        List.of(),
        1,
        TurnLifecycleObserver.noop());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.historySelector = Objects.requireNonNull(historySelector, "historySelector");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.gate = Objects.requireNonNull(gate, "gate");
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.lifecycle = new LifecyclePublisher(observer);
    this.toolLoop =
        new ToolLoop(model, new ToolRegistry(List.copyOf(tools)), lifecycle, maxIterations);
  }

  @Override
  public ChatResult chat(ChatCommand command) {
    return gate.execute(command.sessionId(), () -> execute(command));
  }

  private ChatResult execute(ChatCommand command) {
    lifecycle.emit(TurnLifecycleEvent.turnStarted());
    try {
      var snapshot = sessions.load(command.sessionId());
      var user = new ChatMessage(MessageRole.USER, command.message());
      var messages = new ArrayList<ModelMessage>();
      messages.add(new ChatMessage(MessageRole.SYSTEM, systemPrompt));
      messages.addAll(historySelector.select(snapshot.messages(), limits));
      messages.add(user);
      OffsetDateTime userAt = OffsetDateTime.now(clock);
      var finalContent = toolLoop.complete(messages);
      if (finalContent.isBlank()) {
        throw new InvalidModelResponseException("模型返回了空响应");
      }
      var assistant = new ChatMessage(MessageRole.ASSISTANT, finalContent);
      var turn = new PersistedTurn(user, userAt, assistant, OffsetDateTime.now(clock));
      lifecycle.emit(TurnLifecycleEvent.turnCommitting());
      sessions.appendTurn(command.sessionId(), turn);
      lifecycle.emit(TurnLifecycleEvent.turnCommitted());
      return new ChatResult(command.sessionId(), assistant);
    } catch (RuntimeException failure) {
      lifecycle.emit(TurnLifecycleEvent.turnFailed(failureStatus(failure)));
      throw failure;
    }
  }

  private static String failureStatus(RuntimeException failure) {
    if (failure instanceof ToolLoopLimitExceededException) {
      return "TOOL_LOOP_LIMIT_EXCEEDED";
    }
    if (failure instanceof InvalidModelResponseException) {
      return "INVALID_MODEL_RESPONSE";
    }
    return "TURN_EXECUTION_FAILED";
  }
}
