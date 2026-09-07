# v6 架构审查修复验收

本次在既有 v6 未提交工作树上修复六项审查问题，并补齐 CLI 前台执行、终端审批和结构化输入。
保持 14 个子模块、Protocol v3、冻结配置、精确版本和 `data-v6` 边界；没有增加 RPC、数据库 Schema 或迁移。
App Server 继续拥有执行、权限、预算和持久化，Desktop 与 CLI 通过 Java SDK 使用服务端。

## 修复与回归路径

| 范围 | 实现与验收 |
|---|---|
| 写入型 spawn | 工作树使用真实 `worktree/provision` 方法身份，幂等键保留 `:worktree`；先预留、再工作树、唯一 Turn、派发。真实 H2/ExtensionToolPlatform/Git 测试覆盖同键重试、Git 成功后持久化故障恢复及 reconcile 复用、预算只扣一次。 |
| 父权限重放 | `resolveOrchestrated` 通过已有 `ParentTurnPermissions` 和权威 Thread 执行根恢复父交集，再严格绑定冻结目录。测试覆盖不同 Profile 版本、父级收窄、预留后撤权/取消及三级资源交集和工作树相对路径映射。 |
| CLI 前台与交互 | 服务端终态前保持 SDK/stdio 存活；stdout 为最终 `TurnStartResult`，stderr 为进度、消息和交互。真实子进程记录连接关闭时的终态；受控协议对端覆盖分页、非法答案、受限 JSON、过期、冲突、阻塞 reader、EOF、取消和断线。 |
| 脚本模式 | 不读取管道作为授权；无宿主审批明确拒绝后继续，必需输入取消并返回 2。测试断言 DENIED 的原因和 expectedRevision，未弱化服务端策略。 |
| Role 精确引用 | 名称、指令、能力按字段更新；历史 `ProviderRef` 不在最新目录时仍保留。真实控件覆盖旧模型引用、显式模型切换与继承，以及失败保留草稿。 |
| Role 草稿与导入 | Presenter 统一 dirty/pending 守卫，导入结果合并目录；预览绑定原 expectedRevision，提交后不自动重投。可控 Future 覆盖过期响应隔离、冲突只读比较和确认丢弃、导入/优化与编辑互斥。 |
| 执行配置刷新 | 同作用域 bind 幂等，显式 refresh、页面激活和连接恢复重新加载；目录、配置和精确继承角色完成后整体应用。真实控件覆盖跨作用域竞态、失败重试、冲突恢复、全部显式 Overrides、隐藏限制和名称草稿保留。 |

新增实现复用已有权限交集、SDK facade、CanonicalJson 受限校验、ExecutionSelectionControl、RevisionConflictPane
和 JavaFX 组件样式。移除了原先从空模型控件重建 ProviderRef、导入后全量 reload、启动即关闭 stdio 的重复或错误路径。
新增及重写注释为中文，说明幂等身份、权限重放、进程所有权、输入归属和异步代次。

## 本机验证记录

定向回归与 `mvn spotless:apply`、`mvn spotless:check checkstyle:check`、`git diff --check` 已通过。
定向日志为 `/tmp/javaclaw-review-fixes-targeted.log`，包含 7 项真实 H2/Git 集成测试、22 项 CLI 测试和
29 项 Desktop 相关测试；上游模块另有契约测试。定向计数不与完整构建重复累计。
完整 `clean verify` 通过 1952 项测试，0 失败、0 错误、8 项条件跳过；其中 Desktop 为 274 项单测加 1 项
覆盖 54 图的 Golden。模块覆盖率、日志哈希与跳过项以[构建记录](v6-build-validation.md)为准。
现有源码基准复测两个 JVM、200 样本的 p50/p95 为 154.162/170.204 ms，两个分位数均满足相对 Git v5
已记录对照的 10% 增量阈值，详见[性能及范围](v6-review-fixes-performance.md)。

macOS aarch64 / JDK 25 另行使用真实 PTY 运行 `JavaClawCli.main`，对端为持续运行至取消的
`CliStdioTestServer ... await-cancel`。发送 Ctrl-C 后退出码为 130，标准错误明确显示：

```text
Turn 00000000-0000-0000-0000-000000000002 取消结果未确认：服务端未在期限内确认终态。
```

该次终端信号同时终止了测试服务进程，marker 仍为 `started`，没有记录取消终态。这验证了原生中断下的有界退出和
未知结果提示，不能当作真实 App Server 已持久化取消的证明。SDK 可确认取消、版本冲突重读和超时分支另有自动回归。
原始输出、退出码和对端模式见[终端记录](v6-review-fixes-tty.json)。

## 平台与证据限制

本轮未调用付费模型。模型与协议响应由可控测试实现提供；服务端核心回归使用真实目录校验、H2 和临时 Git 工作树。
测试中受控 Sandbox 能力不等于原生隔离验收。macOS 真实人工审批、真实 App Server 在 Ctrl-C 下的落盘取消，
Linux/Windows 的终端、Ctrl-C 和进程行为仍需目标环境验收；未取得的平台结果不计作通过。

Golden 不通过批量更新参考图掩盖差异。完整构建的视觉结果、准备路径性能及发布限制分别记录，
不以自动测试代替三平台原生、真实模型效果或端到端性能结论。
