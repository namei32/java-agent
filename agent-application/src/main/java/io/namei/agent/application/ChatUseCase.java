package io.namei.agent.application;

/**
 * 对外暴露一次 Agent 对话轮次的应用用例。
 *
 * <p>实现负责会话串行化、历史选择、模型与工具循环、取消检查以及最终轮次提交。调用入口只需要依赖该接口，不应直接编排模型或仓储。
 */
@FunctionalInterface
public interface ChatUseCase {
  /**
   * 执行不可取消、无进度回调的普通对话轮次。
   *
   * @param command 会话和用户输入命令
   * @return 已提交的助手结果
   */
  ChatResult chat(ChatCommand command);

  /** 使用显式取消句柄执行对话；默认实现仅做入口校验并调用普通路径。 */
  default ChatResult chat(ChatCommand command, TurnCancellation cancellation) {
    if (cancellation == null) {
      throw new IllegalArgumentException("cancellation 不能为空");
    }
    return chat(command);
  }

  /** 使用显式取消句柄和流式进度监听器执行对话。 */
  default ChatResult chat(
      ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
    if (progressListener == null) {
      throw new IllegalArgumentException("progressListener 不能为空");
    }
    return chat(command, cancellation);
  }
}
