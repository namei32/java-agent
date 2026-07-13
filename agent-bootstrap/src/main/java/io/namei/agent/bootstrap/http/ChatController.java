package io.namei.agent.bootstrap.http;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatUseCase;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
  private final ChatUseCase chat;

  public ChatController(ChatUseCase chat) {
    this.chat = Objects.requireNonNull(chat, "chat");
  }

  @PostMapping
  ChatResponse chat(@Valid @RequestBody ChatRequest request) {
    var result = chat.chat(new ChatCommand(request.sessionId(), request.message()));
    return new ChatResponse(
        result.sessionId(), new ChatResponse.Message("assistant", result.assistant().content()));
  }
}
