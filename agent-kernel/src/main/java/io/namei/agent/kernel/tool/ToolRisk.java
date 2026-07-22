package io.namei.agent.kernel.tool;

/** 工具声明的副作用风险，用于决定是否允许执行以及是否必须审批。 */
public enum ToolRisk {
  /** 只读取数据，不改变本地或外部状态。 */
  READ_ONLY,
  /** 修改 Agent 本地受控状态。 */
  WRITE,
  /** 会影响外部系统、账户或人员的操作。 */
  EXTERNAL_SIDE_EFFECT
}
