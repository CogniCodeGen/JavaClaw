# v6 本机构建验收

15 个 Reactor project 完整 `clean verify` 通过：1952 项测试，0 失败、0 错误、8 项条件跳过。

本记录包含六项架构审查修复及 CLI 前台交互。运行于 macOS aarch64、JDK 25；Spotless、Checkstyle、严格 Schema、
依赖分析、架构边界、覆盖率及独立 JVM 的 54 图 Golden 均通过，本轮没有更新 Golden 参考图。

```bash
mvn --batch-mode --no-transfer-progress -Djavaclaw.update.ui.golden=false -Djavaclaw.performance.gate=true clean verify
```

另行执行 `mvn spotless:check checkstyle:check` 与 `git diff --check` 均通过。

| 模块 | 测试 | 跳过 | 行覆盖率 | 分支覆盖率 |
|---|---:|---:|---:|---:|
| javaclaw-agent-runtime | 35 | 0 | 96.70% | 81.78% |
| javaclaw-api | 93 | 0 | 93.08% | 80.12% |
| javaclaw-app-server | 731 | 1 | 91.05% | 80.63% |
| javaclaw-browser-service | 47 | 0 | 81.67% | 71.10% |
| javaclaw-builtin-contracts | 53 | 0 | 88.62% | 71.78% |
| javaclaw-builtin-extensions | 161 | 0 | 90.05% | 70.95% |
| javaclaw-client | 88 | 0 | 87.84% | 72.08% |
| javaclaw-desktop | 275 | 0 | 88.56% | 74.03% |
| javaclaw-extension-spi | 60 | 0 | 94.79% | 82.07% |
| javaclaw-knowledge-worker | 7 | 0 | 92.59% | 76.67% |
| javaclaw-model-adapters | 62 | 0 | 89.30% | 75.05% |
| javaclaw-native-hosts | 114 | 5 | 82.51% | 72.81% |
| javaclaw-packaging | 87 | 2 | 82.63% | 76.67% |
| javaclaw-protocol | 139 | 0 | 91.59% | 82.16% |

完整计数、条件跳过列表和日志 SHA-256 见[机器可读记录](v6-build-validation.json)。
原始本机日志：`/tmp/javaclaw-review-fixes-verify.log`。

[本轮修复及终端验证](v6-review-fixes-validation.md)记录真实 H2/Git、stdio 和 JavaFX 测试路径及限制。
配置准备性能使用[独立源码基准](v6-review-fixes-performance.md)另行复测；此前导航文案的视觉审阅见
[逐图记录](v6-settings-golden-review.md)。

本机通过不代表三平台发布就绪。条件跳过的真实模型、系统凭据或目标操作系统测试，以及原生安装、Browser/OAuth、
签名和公证仍须独立验收。macOS Ctrl-C 只验证有界退出和取消未知结果提示，不能证明真实 App Server 取消落盘；
Linux/Windows 终端行为未验证。默认测试未调用付费模型。
