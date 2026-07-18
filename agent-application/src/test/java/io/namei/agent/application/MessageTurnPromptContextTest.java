package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MessageTurnPromptContextTest {
  @Test
  void forwardsTheTrustedInboundChannelSessionAndTimestampToChat() {
    var command = new AtomicReference<ChatCommand>();
    ChatUseCase chat =
        input -> {
          command.set(input);
          return new ChatResult(input.sessionId(), new ChatMessage(MessageRole.ASSISTANT, "完成"));
        };
    var turns =
        new MessageTurnService(
            chat,
            OutboundMessageObserver.noop(),
            new PromptTurnContextFactory(ZoneId.of("Asia/Shanghai")));
    var inbound =
        new InboundMessage(
            MessageContract.CURRENT_VERSION,
            "telegram:42:99",
            "turn-99",
            "telegram:42",
            new MessageRoute("telegram", "42"),
            "42",
            "问题",
            Instant.parse("2026-07-18T13:14:15Z"));

    turns.process(inbound, ignored -> {}, TurnCancellation.none());

    assertThat(command.get().promptTurnContext().channel()).isEqualTo("telegram");
    assertThat(command.get().promptTurnContext().sessionId()).isEqualTo("telegram:42");
    assertThat(command.get().promptTurnContext().requestTime())
        .isEqualTo(Instant.parse("2026-07-18T13:14:15Z"));
    assertThat(command.get().promptTurnContext().zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
  }
}
