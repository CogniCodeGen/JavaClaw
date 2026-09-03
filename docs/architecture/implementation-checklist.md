# JavaClaw 5 实施清单

状态只依据当前源码与已取得的本机自动化证据更新。`[x]` 表示对应纵切已经进入当前实现并有聚焦测试或本机证据，
不代表整个发行门禁已经通过；`[ ]` 表示仍缺目标平台或外部发布证据。完整 `clean verify`、外部 Runner、签名和公证
与功能实现分开记录，不能用静态 mock 或设计文档替代。

## 平台基线

- [x] Reactor 收敛为 14 个子模块，版本统一为 `5.0.0-SNAPSHOT`；包含根聚合项目时 Maven Reactor 共 15 个 project，
  Browser 与 Knowledge 各自拥有独立 Worker 模块。
- [x] 建立 Thread / Turn / ItemEnvelope、Protocol v2 和 H2 `data-v5` 空库初始化。
- [x] Protocol v2 catalog 的 147 个方法都有严格 params/result Schema；ID、时间和 Duration 只有一种 wire 表示。
- [x] 建立 Thin Turn Harness、预算、取消、流事件、背压和 EffectReceipt 契约。
- [x] Turn 在模型/工具边界前持久化 phase、intent digest、usage、冻结工具目录与 batch；结果和 checkpoint 同事务推进。
- [x] Extension Job 使用持久化 intent/checkpoint 恢复活动工作单元；无法确认的在途副作用进入 `UNKNOWN_OUTCOME`。
- [x] 输入与审批等待可在 App Server 重启后恢复，重建 lifecycle lease 时不机械拒绝原审批。
- [x] 审批独立过期投影按绝对 `expiresAt` 调度、周期校正，并与 resolve 共用记录锁和 H2 行锁；过期、Item、checkpoint 与 Turn 恢复同事务提交。
- [x] `agent-runtime` 不依赖 Jackson、Spring AI、H2、Quartz、PDFBox、POI 或 JavaFX。
- [x] App Server 是 H2、Provider、Supervisor 与 RPC 的唯一组合根。
- [x] 生产路径没有协议双栈、数据导入、deprecated facade 或复杂领域通用文档/自动化抽象基类。
- [x] 5.0 不包含 Rust/Codex 后端或 Codex Plugin 兼容层，也不恢复 Ollama、本地模型、旧业务 mode、
  SMTP/IMAP/Webhook、宿主鼠标键盘控制、Raw Cookie 导出或 `HOST_FULL_ACCESS` 配置。

## 模型、工具与安全

- [x] Spring AI 通用 Adapter、Provider capability matrix 和 OpenAI Responses 原生扩展。
- [x] OpenAI Responses reasoning summary、opaque state 与 native compaction 专用契约测试。
- [x] 冻结工具目录、渐进发现、schema/revision 校验与实时撤权。
- [x] Provider、Agent Profile、PermissionProfile、Secret Vault 的强类型 RPC、SDK、持久化和管理页面。
- [x] Provider 逐模型声明 Chat/Embedding 用途；安装级 Embedding 使用精确 `ProviderRef` 绑定，不再按名称或隐藏选项选择。
- [x] 四类 Provider 的有界模型目录发现贯通 Adapter、App Server、Protocol、SDK 与设置页；session-owned 的
  start/read/cancel 操作最长 30 秒、最多 1000 条，关闭页面、session 或服务会取消真实 HTTP 调用，结果不持久化。
- [x] Provider 首次设置按“禁用连接壳、凭据、模型用途、启用”使用确定性幂等命令恢复；中断后继续缺失步骤，ID 冲突不覆盖。
- [x] `default/worker/explorer` Profile 预设和 Workspace review/developer 权限预设由服务端版本化目录提供；实例化后仍是
  普通 Profile/PermissionProfile，不增加角色运行时。预设不进入 H2；向导复用普通 create/update/binding RPC，并以
  Workspace 确定性 ID 和幂等键恢复。`standard` 技术模板在 UI 中显示为“受限对话”。
- [x] 本机端到端测试已验证 Provider 配置、Vault Secret、Profile 精确引用驱动 Turn，并在关闭后从同一 `data-v5`
  重建 App Server 再次完成 Turn。
- [x] Provider 配置按不可变 revision 提交；`provider/credential/*` 先构造候选 Adapter，在同一 H2 事务提交 Vault
  密文、Secret 元数据 revision、Provider 新 revision 和幂等回执，再同步交换 registry generation；数据库提交后的
  激活或清理异常不能回滚 H2，运行时保持 fail closed。非计费探测与显式确认的计费 round-trip 分离。
- [x] Prompt provenance 预览和受预算 Harness Turn 的 Prompt 优化 Draft/显式采纳闭环。
- [x] PermissionProfile 标准模板、clone、历史、diff、五层有效权限预览与实时撤权。
- [x] 审批、Sandbox、PTY 和 EffectReceipt 执行链。
- [ ] Secret Vault 的 Linux/Windows 系统凭据设施真实 Runner 验证。`待验证`
- [x] macOS Seatbelt、PTY 与 Git Worktree 真实本机测试。
- [ ] Linux bubblewrap、PTY、资源和安装启动由 Linux x64/arm64 Runner 验证。`待验证`
- [x] Windows AppContainer、Restricted Token、Job Object、ConPTY、ACL 恢复和进程树终止实现及跨平台契约测试。
- [ ] Windows 原生实现由 Windows x64 Runner 验证；Job Object 不提供打开文件数硬限制。`待验证`

## 扩展与客户端

- [x] Plan、Loop、Workflow、SDD 与 Schedule 的 Definition/Execution、Outbox、checkpoint、恢复状态机和管理纵切。
- [x] Memory、Knowledge 与 Skill 的历史、Proposal、Generation、Draft/Published、冻结目录和管理纵切。
- [x] MCP `2026-07-28` Host、OAuth 2.1/PKCE、Catalog、实时复核、Broker、SDK 与管理页闭环。
- [x] MCP Resource list/read、Prompt list/get、多帧 SSE、受控 progress，以及复用 InputRequest/Turn 的 elicitation/sampling 闭环。
- [x] Site/Browser 的 Broker-only 网络、Vault 会话、十分钟人工登录、Secret 遮罩、SDK 与 ViewSchema 纵切；同一 Site
  页面提供 Secret 脱敏目录、创建、轮换和永久清除，操作后按 revision 刷新权威视图。
- [x] Site 的 CredentialRef、私网授权与精确 Origin 绑定 authority revision；变更后旧 Browser 会话立即失效。
- [x] Browser 与 Knowledge Worker 使用独立 image、Native Sandbox、资源上限和 Broker/无网络边界。
- [ ] 五个目标 Runner 的真实 Chromium 登录/OAuth 与 Native Sandbox 回执。`待验证`
- [x] SDK typed facade、stdio/UDS 连接、CLI 与 SDK-only JavaFX Desktop。
- [x] 三份 IDEA 共享运行配置支持 App Server、Desktop 与组合一键调试；自动架构测试锁定模块入口、主类、UDS 和隔离
  `data-v5` 参数。
- [x] `extension/event` 仅携带资源标识和 revision；SDK 持续接收，Desktop 合并失效并重新读取权威状态。
- [x] ViewSchema v2 policy、受限 renderer、平台 Graph 控件及 dirty/revision conflict 草稿保护。
- [x] 单实例设置与管理中心、九主题、四档字号、三档密度和共享组件。
- [x] 29 个生产管理入口均接入强类型 SDK 或 ViewSchema v2；学习策略由 Memory 独立 ViewDocument 拥有，不保留重复
  Desktop 表单；Desktop 覆盖率和 Golden 继续作为独立发布证据。
- [x] Managed Worktree 的父子 Thread 绑定、隔离根、Patch、备份后 cleanup 和受治理 apply 管理纵切。
- [x] 第三方 Bundle 的 digest staging、签名/信任审阅、原子升级、进程监督、配额、隔离和可恢复 Trash 管理纵切。
- [x] lifecycle lease、固定 60 秒退出、Schedule 登录启动项协调和托盘控制实现。
- [x] Plan 模型只能提交等待人工审阅的 Proposal；采纳原子复核候选 hash 与目标 Definition revision。
- [x] Schedule Action 冻结参数、Catalog revision 与 schema hash；无人值守授权原子消费，`UNKNOWN_OUTCOME` 消耗额度且禁止重试。
- [x] Skill v5 Markdown/确定性 Bundle 通过 Attachment 导入导出，旧 Bundle 与一般 ZIP 不进入兼容解析。
- [x] Diagnostics 聚合 Extension 启停/隔离/信任层数量与 launcher/tray 真实可用状态，导出仍只包含协议白名单脱敏字段。
- [x] Windows Named Pipe transport、当前用户安全描述符和 launcher 实现。
- [ ] Windows Named Pipe、launcher、登录启动项与托盘由 Windows x64 Runner 验证。`待验证`
- [ ] macOS/Linux 托盘与登录启动项的真实交互验证。`待验证`

## 质量与发布

- [x] Checkstyle 落实 600/60/12/4/7、公共 Javadoc 与 TODO/FIXME 门禁。
- [x] 架构测试将 600 行门禁覆盖到 Java、CSS、FXML 与发布脚本；样式按职责分片并锁定加载顺序。
- [x] Maven Enforcer、dependency convergence 和 undeclared/unused dependency 检查。
- [x] Surefire 在模块没有测试时 fail closed，避免 JaCoCo 因缺少 execution data 静默通过。
- [x] 架构边界测试检查模块集合、关键依赖与历史运行路径。
- [x] CycloneDX SBOM、第三方许可白名单、确定性 SHA-256 清单与篡改测试。
- [x] Linux、macOS、Windows 原生安装脚本和正式标签 fail-closed 签名/公证配置。
- [x] GitHub provenance 与 CycloneDX SBOM attestation 配置。
- [x] 主运行时与 Browser/Knowledge/Skill image 完成依赖隔离；Browser 运行期不下载 Chromium，安装缺少两类真实
  原生能力回执时 fail closed。
- [x] macOS 设置中心壳和外观页已有 54 张生产 Scene Golden；矩阵锁定九主题、三密度、100% 字号和两种窗口，
  并由独立 Failsafe JVM 执行逐字节回归，避免 JavaFX 进程级字形缓存造成测试顺序污染；导航目录另行断言 29 个入口。
- [ ] 29 页正文、多状态和其余三档字号的 macOS 视觉 Golden。`阻断`
- [x] 生产 import graph 无包循环，架构门禁使用最长包名归属解析，能检出父包与子包之间的真实边。
- [x] API、Extension SPI、Protocol 与 Agent Runtime 已分别通过当前模块 90%/80% 覆盖率门禁。
- [x] Model Adapter、Builtin Contracts/Extensions、Knowledge Worker、Native Host 与 Client 已分别通过当前模块
  80%/70% 覆盖率门禁。
- [x] Browser Service 的 47 个自动测试通过，覆盖率为行 81.62%、分支 71.45%，达到 80%/70% 门禁。
- [x] Packaging 本机 `clean verify` 共 87 个测试，行覆盖率 82.58%、分支覆盖率 76.67%，达到 80%/70% 门禁。
- [x] App Server 全量 667 个测试通过，行覆盖率 90.63%、分支覆盖率 80.00%，达到 90%/80% 门禁。
- [x] Desktop 的 254 个单元测试和 1 个 Golden 集成测试通过；单元测试覆盖率为行 88.41%、分支 73.10%，
  达到 80%/70% 门禁。
- [x] 当前改造后的 macOS jlink 发行目录已通过隔离 `data-v5` 的 Protocol v2 initialize 与 Workspace 查询健康检查。
- [x] 2026-09-02 当前 macOS aarch64 工作树通过 Spotless、Checkstyle、依赖分析、架构测试与完整
  `mvn clean verify`；15 个 Reactor project 全部成功，共执行 1801 个测试，0 失败、0 错误，7 个按当前平台条件跳过。
- [ ] 五个原生 Runner、性能/背压、安装启动和真实签名/公证全部通过。`阻断`
- [ ] Linux/Windows 设置中心 Golden 上传，以及主聊天、审批和断线恢复等独立生产 Scene 参考集。`阻断`
