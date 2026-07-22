package io.namei.agent.kernel.tool;

/** 工具调用反馈给模型的稳定终态。 */
public enum ToolResultStatus {
  /** 工具完成并产生有效结果。 */
  SUCCESS,
  /** 工具执行失败，正文包含安全错误说明。 */
  ERROR,
  /** 风险策略或人工审批拒绝执行。 */
  DENIED,
  /** 工具超过配置的执行期限。 */
  TIMEOUT,
  /** 当前 Agent Turn 被协作取消。 */
  CANCELLED,
  /** 同批调用因前置条件失败而未执行。 */
  SKIPPED
}
