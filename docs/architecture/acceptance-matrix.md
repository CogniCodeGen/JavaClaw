# JavaClaw 6 验收矩阵

## 聊天、文档与后台记忆 V3 当前验收

2026-09-08，WebView 聊天、文档预览、后台任务与工作空间记忆图谱已实现，七项审查修复及退订竞争修复完成本机 `verify` 门禁。
全仓尝试的上游模块通过后，从 Desktop 恢复两个下游项目，覆盖 15 个 Reactor project；
2582 项最终测试记录中 2557 项通过、25 项条件跳过，0 失败、0 错误；
桌面 366 项单测及包含 54 张 appearance 页截图的 Golden 均通过，打包产物健康检查退出码为 0。
新增真实 H2 学习闭环与 47 张生产 SDK / 组件 UI 重放单独记录，不用设置页 Golden 代替新增页面验收。
全仓尝试中多进程 WebKit 探针曾提前退出，根因未确认；仅增强失败诊断，恢复门禁已通过，详见证据。
其他四个 Runner 和跨平台安装、签名仍未验收，完整命令、覆盖率、产物与限制见
[审查修复验收](../evidence/chat-webview-memory-v3-review-fixes-validation.md)；此前 2527 条记录保留在
[历史功能与 UI 对比验收](../evidence/chat-webview-memory-v3-functional-ui-validation.md)。
[使用说明](../chat-documents-memory.md) 和 [ADR 0011](adr/0011-web-surfaces-and-memory-learning.md)
分别记录操作入口与架构边界。下方统一 Coding 计数属于本轮改动前的历史结果，不能代替 V3 验收。

## 统一 Coding 历史验收

2026-09-07，在 macOS arm64、JDK 25 上从 clean 执行全部门禁：15 个 Reactor project 全部通过，
2316 项测试，0 失败、0 错误、25 项条件跳过。完整命令、模块覆盖率、跳过清单与日志摘要见
[本轮构建证据](../evidence/unified-coding-build-validation.md)。条件跳过不计为通过。
14 个模块、App Server 唯一组合根及 Desktop SDK 边界保持；Protocol 仍为 v3，方法目录从 157 增至 159。

| 本轮能力 | 当前证据 | 判定 |
| --- | --- | --- |
| 统一聊天与编程 | 同一 Thread 的三 Turn 真实 NPM Harness：聊天、工具发现、读取、联网准备、修改、断网失败、修复、断网成功、继续聊天；固定模型替身，真实进程和回执 | 本机实际流程通过 |
| 文件与补丁 | 相对根、有界读取/搜索、摘要冲突、目录能力、原 inode 恢复槽、部分应用及用户后续编辑保护；真实 macOS Worker | macOS 通过；Windows 原子替换未满足 |
| batch / PTY | 单 Turn 共用执行槽、增量 CAS 输出、UTF-8 分页、stdin 幂等、真实退出、超时、取消、撤权及关闭；未知不重启 | 本机通过 |
| 准备与联网 | npm、pnpm、pip、Maven 的生产 Broker 准备及新断网进程通过；Gradle 因动态本机通信失败 | 四类通过，macOS Gradle 阻断 |
| 托管工具链 | 发行目录、摘要、归档链接/逃逸/大小、租约、版本声明和安装 Job 自动测试；Maven 完整生产下载安装，其他归档有界 GET 前缀 | 部分实际安装通过 |
| 长任务与预算 | 每次模型调用窗口准备、完整工具组、摘要低信任、原生 state 增量、独立压缩 usage、checkpoint 和未知恢复回归 | 本机自动回归通过；无真实模型效果结论 |
| 持久化与兼容 | 六份 V001 原文保持；V002–V004 有序迁移、checksum、部分 DDL、旧输出和未知执行恢复；现有严格 wire Schema 原文保持 | 本机通过 |
| SDK / CLI / Desktop | 执行目录、实时分页、退出码、Diff、未知 Schema、设置准备状态、取消、迟到响应隔离；CLI JSON 与退出码保持 | 本机自动回归通过 |
| JavaFX 设计体系 | 原有 54 图 Golden 通过；新增三种实际控件状态截图已检查，导航差异单独审阅 | 本机通过；其他平台视觉未验 |
| 三平台隔离与发行 | macOS arm64 原生测试和发行构建通过；四个其他 Runner、Windows MSVC/签名服务/WFP/Job/ACL/ConPTY/安装生命周期未取得回执 | 整体发行阻断 |
| 质量门禁 | Spotless、Checkstyle、依赖分析、包循环、各模块覆盖率、Golden、发行依赖/许可和完整 verify | 本机通过 |

本轮未完成需求为 macOS Gradle 完整编译测试、Windows 单次原子可见替换；其余四个 Runner 与签名安装属于
尚未验收的独立条件。未通过放宽整个 localhost、关闭测试或降低门禁来消除这些缺口。
详见[用户说明](../coding.md)、[公开仓库记录](../evidence/coding-public-repository-acceptance.md)、
[安装记录](../evidence/coding-toolchain-installation-validation.md)与[客户端状态记录](../evidence/coding-client-state-review.md)。

## 改动前 v6 基线记录

以下保留原 v6 基线的范围与限制，其 1952 项测试、8 项跳过及旧模块计数是历史证据，不代替上方统一 Coding 的当前结果。
2026-09-02 的 Protocol v2/data-v5 发行健康检查更早于该 v6 基线，不能作为 v6 通过证据。

| 领域 | v6 验收要求 | 当前证据与缺口 | 判定 |
|---|---|---|---|
| API 与模块边界 | 14 模块；移除 AgentProfile/ProfileBinding 业务路径，Role/Execution 契约完整 | 全仓编译、架构边界、包循环和依赖分析通过；14 个子模块及旧业务入口清理接受门禁检查 | 本机通过 |
| Protocol v3 | 157 个 RPC 的严格 params/result Schema；initialize 拒绝 v2；SDK/通知/错误映射一致 | 139 项 Protocol 测试及 SDK 契约回归通过；严格 Schema、v2 拒绝和错误映射接受本次门禁检查 | 本机通过 |
| data-v6 | 新 baseline、幂等/revision/事务；旧 data-v5 sentinel 不变 | 空库、重启、事务/revision 与数据根隔离测试通过；包含父目录 alias 指向 data-v5 时拒绝且 sentinel 不变 | 本机通过 |
| 执行配置解析 | 安装→Workspace→Thread→调用逐字段覆盖；Role 显式约束最后应用；字段来源可见 | 配置继承、Role 锁定、失效引用、冻结、幂等及字段来源的服务端和 API 测试通过 | 本机通过 |
| Role 与模板 | 四内置只读角色、clone、普通 Role CRUD；default fallback；explorer 代码只读 | 内置模板/来源、Role CRUD/只读 clone、default fallback 和 explorer 约束的 API、服务端、SDK/Desktop 测试通过 | 本机通过 |
| Role 文件 | portable/lossless 往返；1 MiB/UTF-8/严格 TOML；预览确认；未知模型映射 | 预览与原 revision 绑定、导入合并目录、dirty/pending 保护及过期异步响应隔离通过真实控件回归；Desktop 文件人工操作仍待检查 | 自动测试通过 |
| Provider 与 Vault | 独立 Provider/Permission；精确模型用途；凭据原子提交、lease、撤销 | 配置热更新、同库重启、凭据提交/lease/撤销与权限不扩张回归通过；真实系统凭据测试条件跳过 | 部分证据 |
| Prompt 与 Adapter | system/developer/contract 分层；外部数据不提升；reasoning 显式映射 | 指令分层、reasoning、能力不匹配、优化采纳与无付费 Adapter 契约测试通过；不据此声明真实模型效果 | 本机通过 |
| Provider state | 指令层/Prompt 变化不复用旧 state；compaction 保留 opaque items | 完整指令层绑定、Prompt 摘要过滤、opaque item 与 compaction 契约测试通过 | 本机通过 |
| Turn 与子任务 | 冻结配置/目录/预算；父上限；EffectReceipt/UNKNOWN_OUTCOME；恢复不解析 latest | 新增 7 项真实 H2/ExtensionToolPlatform/Git 回归：worktree/provision 幂等身份、失败恢复及已有工作树复用、单次预算、不同 Profile 版本和三级父权限/路径映射；既有重启、撤权、自动化回归通过 | 本机通过 |
| Automation | Plan/Loop/Workflow/SDD/Schedule 使用独立选择；持久化冻结配置和预算 | 独立选择表单、typed contracts、状态机和冻结配置恢复的本机回归通过；恢复不重解 latest | 本机通过 |
| Desktop 交互 | SDK-only；Agent Studio；独立 Agent/模型/推理/Permission；继承来源与锁定原因 | 274 项单测全部通过；真实 FXML 壳核对独立 Role/reasoning 的 SDK 请求，草稿、精确引用与锁定场景通过 | 本机通过 |
| 执行配置刷新 | bind 幂等；同作用域主动 refresh；冲突保留草稿；完整快照原子展示 | 页面激活及连接恢复、跨作用域迟到响应、失败重试、确认丢弃和只读比较、隐藏限制/全部 Overrides/名称草稿保留均有真实 JavaFX 与可控 Future 回归 | 本机通过 |
| CLI 前台与交互 | SDK/stdio 活到终态；明确审批与受限 JSON；无宿主不消费管道授权 | client 88 项测试通过，含 22 项 CLI 回归和真实 stdio 子进程；macOS PTY Ctrl-C 返回 130 并报告取消未知，真实 App Server 落盘及 Linux/Windows 终端仍待验收 | 部分证据 |
| 跨入口一致性 | 同一 Workspace/Thread 和显式覆盖在 CLI、SDK、Desktop 产生相同配置与 Prompt 摘要 | 复用同一服务端解析入口；跨入口 transcript 对照待执行 | 待验证 |
| Desktop 视觉 | 沿用 509f197 JavaFX/FXML/CSS；新控件各状态及三平台视觉 | 独立 JVM Golden 通过 54 图逐字节比较；[Agent 导航文案差异已审阅](../evidence/v6-settings-golden-review.md)；新页面多状态、其余字号与其他平台仍缺证据 | 部分证据 |
| Memory/Knowledge/Skill | 历史、Generation、Draft/Published、目录冻结与权限复核 | 服务端、扩展和 Knowledge Worker 相关契约、状态及检索自动回归通过 | 本机通过 |
| MCP/Site/Browser | Broker、OAuth、Vault 会话、实际能力快照、无原始 Cookie 泄漏 | 本机契约与 Broker/OAuth/会话自动回归通过；真实 Browser/Sandbox 能力测试条件跳过，五 Runner 回执未齐 | 阻断 |
| Managed Worktree/Bundle | 隔离根、Patch 备份、受治理 apply；签名、进程监督和限额 | 隔离根、Patch、受治理 apply、Bundle 签名/监督与限额的本机自动回归通过 | 本机通过 |
| macOS Native | Seatbelt、PTY、Git Worktree、取消、目录权限与登录启动 | 本机可用原生测试、目录权限、alias 隔离与登录启动契约通过；真实系统凭据条件跳过，安装与实际交互仍待验收 | 部分证据 |
| Linux Native | bubblewrap、PTY、系统凭据、x64/arm64 安装与数据根 | 缺对应目标 Runner 结果 | 阻断 |
| Windows Native | Named Pipe、AppContainer、Restricted Token、Job、ConPTY、ACL 与安装 | ACL 初始创建、回读、主体信任与危险继承模拟测试通过；Windows 原生测试在 macOS 条件跳过；Job 无打开文件数硬限制 | 阻断 |
| 质量与覆盖率 | Spotless、Checkstyle、依赖分析、包循环；Core 90/80、外围 80/70 | [原 v6 基线记录](../evidence/v6-build-validation.md)已通过；统一 Coding 的本轮结果以上方新记录为准 | 基线通过 |
| 配置与 Prompt 准备性能 | 相同文本、权限、模型和空工具目录下准备路径增量 ≤10% | [本轮记录](../evidence/v6-review-fixes-performance.md)：v6 p50/p95 154.162/170.204 ms，Git v5 已记录对照 502.831/607.918 ms；候选 2 JVM/200 样本，两个分位数阈值断言通过 | 所测准备范围通过 |
| 完整 Turn 性能与效果 | 普通 Turn 端到端 p50/p95 增量、背压与三平台复测 | 准备基准不包含 Turn 最终写入、Harness、模型完成、UI 和 Sandbox；无端到端结论，未调用付费模型，不宣称 Role 效果提升 | 阻断 |
| 发行与供应链 | v6 健康检查；五 Runner；SBOM、许可、双层哈希、签名、公证、attestation | macOS aarch64 jlink/Worker 镜像、发行 ZIP、SBOM、许可及双层哈希生成与校验通过；安装健康检查、五 Runner、签名、公证与 attestation 未齐 | 部分证据 |

## 证据记录规则

改动前 v6 基线的命令与结果以[构建记录](../evidence/v6-build-validation.md)及其机器可读 JSON 为准。Desktop 的 274 项
单元测试和 1 项覆盖 54 图的 Failsafe Golden 共计 275 项，包含在全仓 1952 项中，不重复累计早期聚焦运行。
六项审查修复的实现、回归路径和终端限制见[本轮验收](../evidence/v6-review-fixes-validation.md)。
8 项条件跳过涵盖真实本地 Provider、系统凭据、Windows 专属能力与真实 Browser/Sandbox；跳过不计作通过。

任何待验证或阻断项均不得通过 waiver、跳过测试、降低覆盖率或未经审阅的 Golden 更新改为通过。要求内的参考图
更新必须记录差异范围、更新前后哈希与审阅依据，且保持原比较门禁；本次导航文案更新已完成完整复验。更新结论时附可重放命令、
Runner/架构信息、产物哈希和日志位置。全仓门禁不能代替未取得的目标平台、性能、真实 Browser、签名与公证证据。

### 编程准备网络的增量证据

以下仅记录编程工具本轮新增边界，不替代上面的整套发行门禁；具体测试和待验收项见
[编程网络验证记录](../evidence/coding-network-validation.md)。
公开仓库、托管发行版和独立断网执行另见[实际工具链验收](../evidence/coding-public-repository-acceptance.md)。

| 能力 | 当前证据 | 状态 |
|---|---|---|
| 固定 DNS 的工具链下载 | 本地 framing、私网与不安全重定向拒绝、字节上限及取消测试通过；另有 8 个生产 GET 前缀与 Maven 完整安装 Job 的真实公网证据，见[安装记录](../evidence/coding-toolchain-installation-validation.md) | 局部通过 |
| CONNECT 租约 | 23 项本地 Socket/解析测试通过，覆盖公网 DNS、精确目标、解析限额、连接/字节/期限预算、撤权与迟到连接关闭；没有访问外网 | 局部通过 |
| 原生公开依赖准备 | macOS arm64 上 npm、pnpm、pip 与 Maven 已完成真实准备及新 OFFLINE 进程验证；Maven 另从空依赖缓存复验通过，Gradle 因动态本机 UDP/TCP 需求失败 | 四类本机通过，Gradle 阻断 |
| macOS 文件 Worker/准备网络 | 目录句柄加固前 Native Hosts 全量 148 项中 141 通过、7 平台跳过；真实 Seatbelt 文件/补丁/inventory、代理端口/IPv6/OFFLINE、写根保护及有限 fcntl/文件锁测试通过 | 局部通过 |
| 项目目录能力与补丁恢复 | 最新 Native 全量 198 项中 181 执行通过、17 项条件跳过，包含目录替换、原子交换、旧 fd 晚写保留、冲突回退与真实二进制 Worker；Windows NT 目录/同句柄 ACL 尚待真实 Runner | 本机局部通过 |
| 原子替换与冲突版本保留 | macOS 原子交换/旧 fd 晚写真实验证通过；新增 3 项并发断言通过；Linux exchange 待 Runner，Windows 明确保留两阶段目标缺失窗口，尚不满足该平台原子替换目标 | Windows 差异待解决 |
| Linux PROXY_ONLY | namespace relay、seccomp 构建与 BPF 决策测试；真实 Linux 隔离测试已提供，本机 macOS 不执行 | 阻断平台验收 |
| Windows PROXY_ONLY/发行 | 固定服务/WFP/Job/OVERLAPPED Pipe/打包与真实 CI 签名服务前置已接线；Windows 专属精确代理/IPv6/UDP 测试已提供，但尚无 MSVC、WFP 实机与签名 MSI 生命周期回执 | 阻断平台验收 |
