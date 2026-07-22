package io.namei.agent.kernel.model;

import io.namei.agent.kernel.tool.ToolDefinition;
import java.util.List;
import java.util.Objects;

/**
 * 描述一次与供应商无关的模型调用请求。
 *
 * <p>请求在创建时复制消息和工具定义，避免调用过程中被外部集合修改。至少必须包含一条消息；工具列表可以为空。
 */
public final class ChatModelRequest {
  private final List<ModelMessage> messages;
  private final List<ToolDefinition> tools;

  public ChatModelRequest(List<? extends ModelMessage> messages) {
    this(messages, List.of());
  }

  public ChatModelRequest(List<? extends ModelMessage> messages, List<ToolDefinition> tools) {
    this.messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    if (this.messages.isEmpty()) {
      throw new IllegalArgumentException("模型消息不能为空");
    }
  }

  /** 返回按上下文顺序排列的只读模型消息。 */
  public List<ModelMessage> messages() {
    return messages;
  }

  /** 返回本次调用允许模型选择的只读工具定义。 */
  public List<ToolDefinition> tools() {
    return tools;
  }
}
