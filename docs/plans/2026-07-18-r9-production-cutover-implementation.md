# R9 生产切换与 Python 退役实施计划

- 状态：G0 已冻结；R8 阶段门禁通过后开始 C1
- 前置：R7、R8 已合入并完成离线验收

1. C1：Java-owned Fixture、Kernel 状态/资格/报告 Contract。
2. C2：严格 Sandbox Root Guard、输入清单和无副作用计划。
3. C3：受限备份、Manifest、校验和可读验证。
4. C4：脱敏差异报告、阈值与显式例外。
5. C5：Rehearsal CLI、Runbook 和回退检查点。
6. C6：故障/兼容/完整阶段门禁及人工生产执行授权模板。

禁止：真实 Workspace/Token/网络/部署、停止 Python、双写、自动切换、自动回退、删除原始数据或宣布 Python
退役。C6 结束后只达到“离线生产就绪”，不执行真实生产切换。
