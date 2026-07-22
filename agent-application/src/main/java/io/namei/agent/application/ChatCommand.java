package io.namei.agent.application;

import io.namei.agent.kernel.prompt.PromptTurnContext;
import java.util.Objects;

/**
 * 提交给被动对话用例的输入命令。
 *
 * @param sessionId 内部会话标识，用于串行化执行和读取历史
 * @param message 用户本轮输入；具体长度限制由入口边界负责
 * @param promptTurnContext 当前轮次可选的提示词运行时上下文
 */
public record ChatCommand(String sessionId, String message, PromptTurnContext promptTurnContext) {
  public ChatCommand(String sessionId, String message) {
    this(sessionId, message, null);
  }

  public ChatCommand {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(message, "message");
  }
}
