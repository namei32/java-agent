package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;

public final class ChatService implements ChatUseCase {
  private final SessionRepository sessions;
  private final ChatModelPort model;
  private final ConversationHistorySelector historySelector;
  private final HistoryLimits limits;
  private final SessionExecutionGate gate;
  private final String systemPrompt;
  private final Clock clock;

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock) {
    this.sessions = sessions;
    this.model = model;
    this.historySelector = historySelector;
    this.limits = limits;
    this.gate = gate;
    this.systemPrompt = systemPrompt;
    this.clock = clock;
  }

  @Override
  public ChatResult chat(ChatCommand command) {
    return gate.execute(command.sessionId(), () -> execute(command));
  }

  private ChatResult execute(ChatCommand command) {
    var snapshot = sessions.load(command.sessionId());
    var user = new ChatMessage(MessageRole.USER, command.message());
    var messages = new ArrayList<ChatMessage>();
    messages.add(new ChatMessage(MessageRole.SYSTEM, systemPrompt));
    messages.addAll(historySelector.select(snapshot.messages(), limits));
    messages.add(user);
    OffsetDateTime userAt = OffsetDateTime.now(clock);
    var response = model.generate(new ChatModelRequest(messages));
    if (response == null || response.content().isBlank()) {
      throw new InvalidModelResponseException("模型返回了空响应");
    }
    var assistant = new ChatMessage(MessageRole.ASSISTANT, response.content());
    var turn = new PersistedTurn(user, userAt, assistant, OffsetDateTime.now(clock));
    sessions.appendTurn(command.sessionId(), turn);
    return new ChatResult(command.sessionId(), assistant);
  }
}
