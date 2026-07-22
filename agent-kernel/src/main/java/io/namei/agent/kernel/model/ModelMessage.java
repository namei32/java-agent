package io.namei.agent.kernel.model;

/**
 * 模型上下文消息的封闭类型边界。
 *
 * <p>封闭层次确保适配器只需处理普通文本、Assistant 工具调用和工具结果三种受控消息形态，避免供应商 SDK 类型泄漏到核心层。
 */
public sealed interface ModelMessage
    permits ChatMessage, AssistantToolCallMessage, ToolResultMessage {
  /** 返回消息在对话协议中的角色。 */
  MessageRole role();

  /** 返回可发送给模型的文本内容；具体类型负责定义是否允许为空。 */
  String content();
}
