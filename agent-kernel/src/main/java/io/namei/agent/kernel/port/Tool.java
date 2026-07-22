package io.namei.agent.kernel.port;

import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.Map;

/**
 * Agent 可调用工具的最小运行时边界。
 *
 * <p>{@link #definition()} 提供给模型进行选择，{@link #execute(Map)} 只在应用层完成参数校验、风险策略和审批后调用。实现不应自行绕过应用层策略。
 */
public interface Tool {
  /** 返回稳定的工具名称、说明、参数 Schema、版本和风险声明。 */
  ToolDefinition definition();

  /**
   * 使用已经过边界校验的参数执行工具。
   *
   * @param arguments 与工具 JSON Schema 对应的参数
   * @return 可安全反馈给模型的结构化执行结果
   */
  ToolResult execute(Map<String, Object> arguments);
}
