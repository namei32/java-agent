# 生产切换离线演练手册

## 目的

在不接触真实用户数据、不启动网络渠道、不读取 Secret 的前提下，验证 Java 迁移准备、备份与回退步骤。

## 前提

1. 使用一个尚不存在的新 sandbox 路径；不得是仓库、Home、真实 Workspace 或其父目录。
2. R7/R8 的默认、`failure`、`compat` 门禁均有成功证据。
3. 输入中不存在真实 Token、Prompt、Memory 正文或用户身份数据。

## 演练步骤

1. 运行下列命令创建 sandbox；它只创建 `input/` 与标记，不复制任何数据：

   ```bash
   java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar \
     cutover-plan --sandbox-root=/tmp/namei-r9-rehearsal
   ```

2. 仅向 `input/` 放入人工构造的脱敏样本：`config/config.toml`、`memory/*.md`、`sqlite/*.db` 及其
   `-wal`/`-shm`。不得复制真实用户内容、Token、Prompt 或真实数据库。
3. 运行离线演练；没有 `--offline-evidence` 会安全停在 `DRAFT`：

   ```bash
   java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar \
     cutover-rehearse --sandbox-root=/tmp/namei-r9-rehearsal --offline-evidence
   ```

4. 运行验证，确认 Manifest 可读且差异为零：

   ```bash
   java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar \
     cutover-verify --sandbox-root=/tmp/namei-r9-rehearsal
   ```

5. 按报告模拟“停止单一写入者 → 恢复备份 → 校验计数”的回退检查点。保存只含状态、备份 ID、计数与
   稳定诊断码的报告；任何差异阈值超限、未知执行、备份损坏或缺失观察项均判定演练失败。

## 生产授权门槛

真实生产切换必须另行书面确认目标环境、维护窗口、负责人、备份位置、回退负责人、观察期、成功阈值和停止条件。
本手册和 CI 不构成该授权。
