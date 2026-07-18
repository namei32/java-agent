# R9 生产切换与 Python 退役实施计划

- 状态：C1–C6 已完成；已达到仅离线生产就绪
- 前置：R7、R8 已完成离线验收；R9 不读取真实生产输入

1. C1：已完成 Java-owned Fixture、Kernel 状态/资格/报告 Contract；生产状态不可进入。
2. C2：已完成严格 Sandbox Root Guard、`cutover-plan` 创建标记、allowlist 输入清单和无副作用计划。
3. C3：已完成受限备份、原子 Manifest、SHA-256 和可读验证；不覆盖或删除输入。
4. C4：已完成脱敏差异报告、零阈值默认值与显式拒绝。
5. C5：已完成 `cutover-plan`、`cutover-rehearse --offline-evidence`、`cutover-verify` CLI 与离线 Runbook。
6. C6：已通过 `./mvnw clean verify`、`./mvnw -Pfailure verify`、`./mvnw -Pcompat verify`；检查报告无
   failure/error。人工生产执行授权仍是未来单独步骤，不执行生产命令。

禁止：真实 Workspace/Token/网络/部署、停止 Python、双写、自动切换、自动回退、删除原始数据或宣布 Python
退役。C6 结束后只达到“离线生产就绪”，不执行真实生产切换。
