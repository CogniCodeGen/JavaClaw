# JavaClaw 6 实施与验收清单

`[x]` 表示对应源码改造或明确注明范围的验证已完成；`[ ]` 表示仍需执行、补证或修复。验证结论见
[验收矩阵](acceptance-matrix.md)和[本机构建证据](../evidence/v6-build-validation.md)：2026-09-07，macOS aarch64、
JDK 25 的完整 `clean verify` 通过，1952 项测试，0 失败、0 错误、8 项条件跳过。v5 数字未转记为 v6 证据。

## 架构与配置

- [x] 维持 14 个子模块、App Server 单一组合根、Thin Turn Harness 与 SDK-only Desktop。
- [x] 统一 `6.0.0-SNAPSHOT`、Protocol v3、data-v6 与新的 baseline；旧数据不探测、不迁移、不修改。
- [x] 用 AgentRole/Spec/Ref 取代胖 AgentProfile；删除 ProfileBinding、旧 Role 预设实例化和旧 SDK/RPC 入口。
- [x] 保持 Provider、PermissionProfile、Vault、审批与预算独立；Role 只保存职责、developer instructions 和收窄约束。
- [x] ExecutionDefaults/Overrides/ResolvedTurnConfig 与字段来源贯通 API、服务端、SDK 和界面。
- [x] Protocol v3 目录包含 157 个 RPC；子智能体默认 `execution/subagent/read/update` 与 SDK 独立管理 Provider/reasoning。
- [x] 安装、Workspace、Thread 与调用逐级继承，Role 明确模型/推理约束最后应用。
- [x] Turn、子任务与 Automation 使用服务端解析并冻结配置，恢复不重新解释 latest。
- [x] 四个内置只读角色、default 创建默认、clone 和代码级 explorer 只读约束。
- [x] Role 模板固定公开源码 commit、原文摘要、本地模板摘要与改编说明。
- [x] portable/lossless 文件预览、确认、导出；未知模型要求明确映射，文件不成为实时配置源。
- [x] 本机自动回归确认失效引用、父级权限/原子预算上限、幂等重放、重启恢复和旧目录 sentinel 不变。

## Prompt 与模型

- [x] ModelInstructions 分离 system、developer 与 response contract。
- [x] Responses 独立 developer message；Spring AI 在 Adapter 边界固定顺序合并并保留全部内容。
- [x] reasoning 按厂商显式映射，不支持的取值拒绝，不静默增大预算。
- [x] Provider state 升级绑定完整指令层，保持原生 compaction 与 opaque item 语义。
- [x] Prompt 预览展示来源与摘要；正常受预算 Turn 产生优化 Draft，显式采纳时复核 Role revision。
- [x] 指令层、reasoning、Prompt 变更与 state、优化采纳、能力不匹配测试在统一门禁通过。
- [ ] 真实模型对照效果评估；默认测试不得调用付费模型，未评估不得声明提升。

## Desktop 与扩展

- [x] 复用 509f197 JavaFX/FXML/CSS 与共享控件，不恢复 Runtime、Spring Context 或数据库访问。
- [x] Agent Studio 只管理 Role；移除旧胖 Profile、权限设置向导、Provider 回写和对应冗余夹具。
- [x] Workspace 创建默认 default，Agent/Provider/模型/推理/Permission 独立选择。
- [x] 显示继承值、来源、revision、模型 Agent 锁定原因和 Turn 配置摘要。
- [x] Schedule 与其他自动化表单提交独立执行配置，使用权威目录。
- [x] 审阅并更新 54 张 macOS 壳/外观页参考中的 Agent 导航文案；框外像素完全一致，比较门禁未变，
  [差异与哈希记录](../evidence/v6-settings-golden-review.md)可追溯。
- [x] Desktop 274 项单测和独立 JVM 的 54 图 Golden 全部通过，包含独立选择、锁定、草稿及真实 FXML 壳交互。
- [ ] 完成 Agent 文件人工交互与错误态检查，以及 CLI/SDK/Desktop 同轮完整 transcript 对照。
- [ ] 补足 Agent Studio/执行选择器/其余页面、多状态、字号以及 Linux/Windows 视觉证据。

## 质量与发布

- [x] 完成六项审查修复：工作树命令身份、父权限重放、CLI 生命周期、Role 精确模型引用、导入草稿保护和主动配置刷新。
- [x] CLI 前台等待、明确终端审批、受限单行 JSON、非交互拒绝审批后继续、必需输入取消及真实 stdio 子进程回归。
- [x] macOS 真实 PTY Ctrl-C 验证退出 130 并明确报告取消未知；该记录不作为服务端取消落盘证明。
- [ ] 真实 App Server 在 Ctrl-C 下的持久化取消，以及 Linux/Windows 终端和进程验收；详见[本轮记录](../evidence/v6-review-fixes-validation.md)。

- [x] 现行文档、ADR、IDEA 配置与 CI 的 v6 入口更新；历史 ADR 标记取代关系。
- [x] 安全、Sandbox、审批、预算、取消、EffectReceipt、Bundle、MCP 与 Worker 的现有执行边界继续复用。
- [x] `mvn spotless:apply` 后检查 diff；完整门禁及另行全仓 `mvn spotless:check checkstyle:check` 均通过。
- [x] 全仓 `mvn clean verify`、严格 Schema、依赖分析、包循环与架构门禁通过。
- [x] Core 90%/80%、外围 80%/70% 最新覆盖率通过，不降低门槛或排除核心类。
- [x] 本轮配置/Prompt 准备复测：v6 p50/p95 约 154/170 ms，Git v5 已记录对照约 503/608 ms；两轮共 200 样本的准备增量满足 ≤10%，
  原始样本、断言与限制见[性能记录](../evidence/v6-review-fixes-performance.md)。
- [ ] 完整普通 Turn 端到端 p50/p95、背压和三平台测量；准备基准不替代最终写入、Harness、模型与 Sandbox 成本。
- [ ] macOS arm64/x64、Linux arm64/x64、Windows x64 原生安装、数据根和安全能力验收。
- [ ] 五 Runner 的真实 Chromium 登录/OAuth、Sandbox 回执和系统凭据设施验收。
- [x] 本机 macOS aarch64 jlink/Worker 镜像、发行 ZIP、SBOM、许可和双层哈希生成与校验通过。
- [ ] 安装后健康检查、目标平台签名、时间戳、公证与 attestation。
