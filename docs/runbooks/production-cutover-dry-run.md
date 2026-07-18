# 生产切换离线演练手册

## 目的

在不接触真实用户数据、不启动网络渠道、不读取 Secret 的前提下，验证 Java 迁移准备、备份与回退步骤。

## 前提

1. 使用新建、脱敏的 sandbox 副本；不得是仓库、Home、真实 Workspace 或其父目录。
2. R7/R8 的默认、`failure`、`compat` 门禁均有成功证据。
3. 输入中不存在真实 Token、Prompt、Memory 正文或用户身份数据。

## 演练步骤

1. 运行 `cutover-plan --sandbox-root <new-sandbox>`，确认只列出类别、计数和 Hash。
2. 运行 `cutover-rehearse --sandbox-root <new-sandbox>`，生成新的备份目录与 Manifest。
3. 运行 `cutover-verify --sandbox-root <new-sandbox>`，检查 Manifest、SQLite 与脱敏差异报告。
4. 按报告模拟“停止单一写入者 → 恢复备份 → 校验计数”的回退检查点。
5. 保存报告；任何差异阈值超限、未知执行、备份损坏或缺失观察项均判定演练失败。

## 生产授权门槛

真实生产切换必须另行书面确认目标环境、维护窗口、负责人、备份位置、回退负责人、观察期、成功阈值和停止条件。
本手册和 CI 不构成该授权。
