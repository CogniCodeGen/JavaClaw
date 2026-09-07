# Turn 配置准备基准

`compare-turn-preparation.py` 从指定 Git commit 导出旧源码，复制当前工作树源码，分别用 `javac`
重新编译真实 `TurnCommandFactory.resolve` 依赖闭包。classpath 只包含脚本列出的第三方依赖 JAR；
不会加载任何模块的 `target/classes`，不会读取或反编译旧构建产物。

基准使用同一个固定用户消息、空角色说明、空项目约定、空工具目录和固定模型能力。模型 `invoke`
与 Sandbox 调用一旦发生立即失败，因此不产生付费模型调用或外部进程。先读取 v6 的实际拼接后
Prompt，再把相同文本作为 v5 空 Profile 的完整平台说明，并逐字核对两者最终文本；指令分层和
持久快照结构保持各版本的真实实现。

```bash
python3 scripts/benchmarks/compare-turn-preparation.py \
  --baseline 438aa5fb --warmup 20 --samples 100 --forks 2 \
  --output docs/evidence/v6-turn-preparation-performance.json
```

只测新候选并复用已有旧版本对照时：

```bash
python3 scripts/benchmarks/compare-turn-preparation.py \
  --baseline 438aa5fb --current-only --warmup 20 --samples 100 --forks 1 \
  --reference-report docs/evidence/v6-turn-preparation-before-batching.json \
  --output docs/evidence/v6-turn-preparation-performance.json
```

每个版本、每个 fork 都使用新的临时目录和独立 JVM（固定 256 MiB 堆）。脚本交替两个版本的先后
顺序，并保存所有纳秒样本、nearest-rank p50/p95、JDK、系统、源码与资源摘要。正式比较期间应暂停
Maven、其他测试和高负载任务。默认 200 次预热、每 fork 1000 次采样、3 个 fork；文件 H2 的每次
打开/关闭可能使完整默认运行耗时较长，可明确降低样本数做诊断，但不得隐瞒样本量。

旧源码的 `H2Database` 强制目录名为 `data-v5`，所以旧基准仅在随机新建的系统临时目录下建立空的
同名数据库；不会访问已有用户数据或工作区历史 `data-v5`。v6 只初始化独立 `data-v6`。临时目录和
生成类保留在结果的 `scratch` 路径，便于复核。

测量范围是配置选择、权限解析、空目录冻结、项目约定读取和 Prompt 快照编码。计时不包含最终
Turn INSERT、Harness 执行、Provider 调用、完整进程启动、UI 或原生沙箱。由于 classpath 基准省略
JPMS descriptor，它也不替代模块门禁。结果只能证明这条准备路径的测量值，不能单独证明完整
普通 Turn、全部平台或发行验收通过。
