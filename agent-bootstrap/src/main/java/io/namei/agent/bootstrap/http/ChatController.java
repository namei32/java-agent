package io.namei.agent.bootstrap.http;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.PromptTurnContextFactory;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
  private final ChatUseCase chat;
  private final PromptTurnContextFactory promptContexts;

  public ChatController(ChatUseCase chat) {
    this(chat, null);
  }

  @Autowired
  public ChatController(ChatUseCase chat, PromptTurnContextFactory promptContexts) {
    this.chat = Objects.requireNonNull(chat, "chat");
    this.promptContexts = promptContexts;
  }

  @PostMapping
  ChatResponse chat(@Valid @RequestBody ChatRequest request) {
    ChatCommand command =
        promptContexts == null
            ? new ChatCommand(request.sessionId(), request.message())
            : new ChatCommand(
                request.sessionId(),
                request.message(),
                promptContexts.create("http", request.sessionId()));
    var result = chat.chat(command);
    return new ChatResponse(
        result.sessionId(), new ChatResponse.Message("assistant", result.assistant().content()));
  }
}
