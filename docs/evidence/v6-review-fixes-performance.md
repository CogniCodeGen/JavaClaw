# 架构审查修复后的配置准备性能

本轮沿用现有源码基准，以 Git v5 `438aa5fb` 的已记录样本为对照。修复候选的 p50/p95 均未超过
原定 10% 增量阈值；脚本核对 commit、拼接 Prompt 摘要和对照报告哈希后，对两个分位数执行断言，结果通过。

| 源码 | JVM 数 | 计时样本 | p50 | p95 |
|---|---:|---:|---:|---:|
| 已记录 Git v5 对照 | 2 | 200 | 502.831 ms | 607.918 ms |
| 本轮 v6 修复候选 | 2 | 200 | 154.162 ms | 170.204 ms |

两个候选 JVM 分别预热 20 次、采样 100 次，固定 256 MiB 堆，使用独立临时 H2；分位数按合并样本 nearest-rank
计算。本轮未重新测量旧版，对照来源和哈希完整保留。样本规模有限，不提供置信区间或模型质量结论。
基准期间已结束 Maven、测试及其他构建，没有并行负载测试。

```bash
python3 scripts/benchmarks/compare-turn-preparation.py \
  --baseline 438aa5fb --current-only --warmup 20 --samples 100 --forks 2 \
  --reference-report docs/evidence/v6-turn-preparation-before-batching.json \
  --output docs/evidence/v6-review-fixes-performance.json
```

[原始候选样本](v6-review-fixes-performance.json)、[对照样本](v6-turn-preparation-before-batching.json)和
[阈值计算及哈希](v6-review-fixes-performance-gate.json)可追溯。阈值计算为
`(candidateQuantile / baselineQuantile - 1) * 100 <= 10`，p50 和 p95 分别判定。
本机日志为 `/tmp/javaclaw-review-fixes-performance.log`。

基准直接从 Git 与当前源码编译，不加载历史 target 类或反编译；模型 invoke 与 Sandbox 调用均禁止。
只测 `TurnCommandFactory.resolve` 的配置读取、权限解析、空目录冻结和 Prompt 准备，不包含最终 Turn INSERT、
Harness、模型完成、UI、原生沙箱或端到端吞吐。该结果不代替三平台或完整 Turn 性能验收。
