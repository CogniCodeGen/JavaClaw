# v6 Turn 配置准备性能记录

此页保留架构审查修复前的历史测量；当前修复候选的复测见[本轮性能记录](v6-review-fixes-performance.md)。

本次配置与 Prompt 准备路径的测量没有出现超过 10% 的增量。最终候选相对 Git 旧版对照，
p50 变化 -68.2%，p95 变化 -71.2%。这不是完整普通 Turn
或多平台发布验收结果。

| 源码版本 | 独立 JVM | 计时样本 | p50 | p95 |
| --- | ---: | ---: | ---: | ---: |
| Git v5 `438aa5fb` | 2 | 200 | 502.831 ms | 607.918 ms |
| v6 分层独立事务候选 | 2 | 200 | 309.232 ms | 349.257 ms |
| v6 同一只读事务读取配置链 | 1 | 100 | 159.929 ms | 174.969 ms |

每个 JVM 预热 20 次，使用新建空数据库、256 MiB 固定堆。第一组两个版本交替先后执行，保存
每次纳秒耗时并用 nearest-rank 算法计算分位数。最终候选只重测一次 JVM，使用第一组相同旧版
对照；样本量有限，没有给出置信区间。测量期间暂停了 Maven 与测试运行。

环境：`macOS-26.5.2-arm64-arm-64bit-Mach-O`。JDK：`java version "25" 2025-09-16 LTS`。
Git 基线完整 commit：`438aa5fb045ed0e3190c0e4c9d6db18179ab68c9`。

计时直接调用各自源码的 `TurnCommandFactory.resolve`，包含权威配置读取、权限解析、空工具
目录冻结、项目约定读取与 Prompt 快照编码。fake ModelGateway 只返回相同固定能力，任何
模型 `invoke` 和 Sandbox 调用都会使基准失败，没有付费模型或网络调用。

先读取 v6 空角色实际拼接后的说明，再将相同文本用于 v5 空 Profile 的完整平台说明；最终
拼接文本逐字相等，SHA-256 为 `cbb4d084004c1bccee5425508a65262295b25e03462e668d61bab124e4eacfb6`。
分层与快照编码仍使用各版本实际实现，用户消息、权限、模型和工具集合相同。

原始证据：[优化前及 Git 对照](v6-turn-preparation-before-batching.json)、
[最终候选](v6-turn-preparation-performance.json)。最终记录包含各版本编译源码及资源的 SHA-256，
并通过原始报告 SHA-256 引用旧版样本。首次原始报告保留未整合前的实际结果，没有覆盖旧样本。

最终候选编译源码 SHA-256：`c3a91fa86a9a8be53c45c5c58f9c8d37070d865cef5d5ad9119f01c51da9920b`；
资源 SHA-256：`a5ce4c2134b4da0a77335e5b208ec5dc652a0f8ad4589ef482fd3a6eb12c9a66`。
本次报告 SHA-256：`91d1687b45eece790ef001d9110d4278cc6a8443102872c448dc15f8c362a164`。

复跑方法见 [基准说明](../../scripts/benchmarks/README.md)。脚本使用 `git archive` 提取基线，
从源码重新 `javac` 编译，classpath 只有新生成类和第三方 JAR，从不使用 `target/classes` 或
反编译。为了运行未经修改的旧版数据库入口，只在新建随机临时目录下初始化空 `data-v5`；
没有访问任何已有用户历史数据。

这些结果不包含 Turn 最终 INSERT、Harness 执行、真实或假的模型完成延迟、进程启动、UI 和
原生沙箱，也没有验证 JVM 模块路径性能。它说明本机本次快照的准备路径满足所测增量阈值，
不替代完整 Turn 端到端对照、完整 Maven verify 和目标平台安装/沙箱门禁。
