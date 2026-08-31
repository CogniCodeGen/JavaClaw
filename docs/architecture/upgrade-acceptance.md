# JavaClaw 4.0 能力恢复验收矩阵

更新时间：2026-08-29。本文以生产调用、行为测试和真实窗口验收为依据，不以类名、表或按钮存在作为完成证明。
本次补齐了原先列为“待实现”的领域链路；当前可交付本机测试版，正式跨平台 Release 仍受第 4 节外部门禁约束。

## 1. 产品与内核闭环

| 能力 | 当前实现与约束 | 行为证据 |
|---|---|---|
| 原桌面风格 | 保留 `509f197` 的十份 CSS、九个主题、默认翡翠、侧栏、标题和输入区；新增页面复用原表单与卡片 | DesktopVisualContractTest、真实 DesktopLaunchSmokeTest；不是全尺寸逐像素等价声明 |
| Chat / 富文本 / 附件 | Thread 历史、流式恢复、Markdown 标题/列表/代码/表格、真实图片输入、附件查看与显式导出 | TranscriptStreamBufferTest、MarkdownDocumentTest、ArtifactViewerTest、AttachmentModelInputTest |
| Agent Studio / Prompt | 版本化 Profile、工具选择、模型与预算；预览不调用模型；优化只生成草稿，采用后单独保存 | PromptWorkflowIntegrationTest、真实 Studio 页面 |
| AGENTS.md | 全局与项目根到工作目录的 override/标准/fallback 解析；32 KiB 项目预算、越界拒绝、Thread 快照和只读状态页 | AgentsInstructionResolverTest、PromptCompilerTest、AgentsInstructionsPane |
| Plan | 只读治理、结构化目标/步骤/验收、明确采用与决策绑定，采用后新 Turn | GovernedToolRuntimeTest、PlanAdoptionIntegrationTest |
| 模型压缩 | 官方 OpenAI Responses 使用 opaque compaction，其他端点使用无工具/无 Schema 自由文本摘要；成功后才原子替换窗口，不删 transcript | OpenAiResponsesGatewayTest、CompactionTranscriptTest、CompactionIntegrationTest |
| Loop | 有限领域迭代、实际验收、无进展停止、预算与检查点；耗尽不代表成功 | AutomationExecutionTest、H2AutomationRepositoryTest |
| Workflow | START/END/AGENT/TOOL/CONDITION/TRANSFORM/HUMAN_INPUT/OUTPUT、有界循环、节点检查点；Agent 节点用子 Thread | AutomationExecutionTest、AutomationDocumentsTest |
| SDD / OpenSpec | 提案→规格→设计→任务→实现→核验→补做/归档；审批绑定产物哈希，显式 OpenSpec 导入导出 | AutomationExecutionTest、OpenSpecDefinitionTest、OpenSpecBundleCodecTest |
| Schedule | H2 权威、Quartz RAM 触发、稳定 Thread、SKIP、不补跑；恢复使用新 Turn 和剩余预算 | AutomationLifecycleTest、H2AutomationRepositoryTest |
| 副作用与恢复 | PENDING 意图先于执行；CONFIRMED 复用结果；UNKNOWN 不自动重发；检查点/预算/事件同事务 | GovernedToolRuntimeTest、H2AutomationRepositoryTest、DefaultAgentRuntimeIntegrationTest |
| Memory / Persona | 事实/情景/关系/纠错、固定、历史和恢复；冲突、Persona 与固定内容覆盖需确认 | H2MemoryRepositoryTest、KnowledgeMaintenanceTest |
| Knowledge | PDF/TXT/Markdown/CSV/JSON/XML/HTML/DOCX 隔离解析；H2 chunk 与来源；索引 generation 完成后切换；无 Embedding 降级关键词 | H2KnowledgeRepositoryTest、IsolatedWorkerTest |
| Skill | 按需完整读取、版本/资源/Bundle、脚本子 JVM、提案/审阅/回滚；启停与 revision 在执行前复核 | H2SkillLifecycleTest、SkillBundleCodecTest、GovernedToolRuntimeTest |
| 学习维护 | OFF/SUGGEST/AUTO，默认建议；低风险且可验证内容才允许自动新增；低优先级有限 Turn，不递归维护 | KnowledgeMaintenanceTest、V008 inbox 与 KnowledgeMaintenanceScheduler |
| 文件 / 代码 / PTY / OCR | 文件和补丁进入受控 Worker；JShell 子 JVM 不继承 App Server classpath；PTY 输入/resize/signal/取消；OCR 单批最多 20 页 | IsolatedWorkerTest、TerminalToolProviderTest、PlatformBackendsTest |
| Browser / 站点 | 页面引用、导航/点击/填充/选择/多标签、上传、Blob 下载、Broker 显式链接下载、截图/PDF、可见人工登录、SecretRef 和加密会话 | BrowserServiceRuntimeTest、BrowserSiteServiceTest、真实 NativeBrowserInteractionTest |
| 邮件 / 通知 | 仅外部 MCP；按来源/工具/Schema/revision/固定接收者/额度/期限授权；已提交不冒充已送达 | ToolAuthorizationServiceTest、CliResourcesIntegrationTest；真实业务送达仍需用户配置服务 |
| 私网端点 | 默认拒绝；明确绑定工作区、用途、origin、IP、期限与版本；撤销立即复核，元数据/控制地址不可授权 | NetworkGrantServiceTest、HttpNetworkBrokerTest |
| Plugin / MCP | ZIP 审阅、签名与权限分离、隔离进程/健康/退避/Trash；MCP 固定 2026-07-28，HTTP/SSE/OAuth/MRTR/Tasks | PluginBundleInstallerTest、PluginRuntimeTest、McpClientTest、McpOAuthServiceTest |
| 子智能体 / 工作树 | 父子共享预算/取消/配额；脏工作区合成基线、临时 index、三方应用；非 Git 单写者，其余只读 | CollaborationServiceGitTest、WorkspaceWriteCoordinatorTest、BudgetAccountTest |
| 工作树人工恢复 | SDK/CLI/原风格恢复页查看父子任务、导出补丁/备份、取消及版本化显式清理；不提供绕过审批的 UI apply | WorktreeRecoveryIntegrationTest、H2CollaborationRepositoriesTest；Runtime 目录维护租约阻止活动目录被清理 |

工作树清理先保存 tracked 与非 ignored untracked 文件的 binary patch 和幂等意图；ignored 文件不备份。
冲突/清理失败保留现场。合并仍由父 Turn 显式执行，用户真实 Git index 不变。
目录维护租约覆盖同路径的其他 Thread/fork，物理执行未退出前不能删除目录。

## 2. 模型、协议与安全验证

- OpenAI 使用官方 SDK Responses JSON 协议，Anthropic、Google 使用真实 Spring AI/供应商 SDK SSE 协议；
  全部由本地假 HTTP 服务驱动。
  验证构造、Provider 专用 options、中文流式文本、usage、客户端资源关闭；不产生模型费用。
- 修复 Jackson 3 与共享 annotations 不匹配、通用 options 强制转换失败、第三方 stdout 日志污染 JSONL、
  SDK 关闭顺序死锁与主动退出误触发重连；不是靠禁用日志测试或关闭门禁绕过。
- JSON-RPC v1 保持旧表示，新增方法为兼容扩展；公开 SDK 不泄漏 Wire/Jackson/Repository。
- V001/V002 保留原哈希；V003–V009 为增量。V009 在备份后删除旧规则表并迁移 ConversationWindow。
  投影、生命周期、Event、Outbox 和执行检查点保持原子性。
- 文件库升级前先校验完整历史，使用 H2 `BACKUP TO` 生成 owner-only ZIP 与 SHA-256；备份失败不执行新 DDL。
  H2MigrationBackupTest 实际恢复副本并检查旧表结构/数据；拒绝 3.x/未标记目录时不改变原权限和内容。
- Launcher 控制帧、stderr、delta 与客户端订阅均有界；异常关闭输出但进程未退出、阻塞 stdin、超大帧均有攻击测试。
- macOS Seatbelt/PTY、UDS 当前用户、受保护目录/符号链接、隔离 Worker、实际 Chromium 树已在本机执行。
  不能由此推断 Linux namespace/seccomp 或 Windows ACL/Job/ConPTY 通过。

## 3. 客户端与发行

桌面真实 smoke 测试通过产品 Launcher 启动 App Server，用 SDK 建立临时数据和真实流式 transcript，
依次打开 Agent Studio、Memory、Knowledge、Skill、Automation、Schedule、Plugin、MCP、站点、
项目约定、工作树恢复、模型设置；检查加载错误、编辑入口、Markdown、连接和正常退出。

`javaclaw-packaging` 将匹配 driver 版本的 Chromium/headless shell/ffmpeg、许可证及文件哈希纳入发行物。
ZIP 保留可执行位和受控符号链接；安装包包含内部 Server/Native/Browser 启动资源。
运行时不自动下载浏览器、不读取个人认证缓存；缺少精确资源时失败关闭。
完整命令、测试总数、实际产物及性能样本见 [验证报告](upgrade-verification.md)。

## 4. 不能由当前工作区代替的外部验收

| 项目 | 需要的外部条件 |
|---|---|
| Linux arm64/x64、Windows x64、macOS x64 | 对应原生 Runner，执行 namespace/ACL/reparse/ConPTY/Named Pipe、安装/启动与攻击测试 |
| 正式同版本 Release | 所有平台同时通过，Apple 签名与公证、Windows Authenticode、Linux 包签名凭据 |
| 三家模型提示词质量 A/B | 用户批准的真实凭据、合成数据、明确费用预算；本地假服务不能证明质量提高 |
| 真实 MCP 邮件/通知与 OAuth 登录 | 用户选择并授权的服务；契约测试不证明真实消息送达 |
| 长稳与旧版全产品性能对照 | 固定规格 Runner、可比的旧版合成基线及长时间压力运行 |
| 浏览器/依赖安全维护 | 发行前审阅锁定版本与漏洞信息；功能测试不等同于漏洞审计 |

这些是发布/外部验证条件，不能伪造通过，也不能用关闭沙箱或 skip 原生测试消除。
未配置能力必须显示原因；不以固定成功结果或占位按钮冒充可用。

## 5. 明确不恢复

Ollama/Deliverance/本地模型资产、宿主鼠标键盘/截图/剪贴板 Agent 控制、内置 SMTP/IMAP/Webhook、
3.x 数据/Plugin API 迁移、旧 MCP 协议回退、远程 TCP/WebSocket、多租户和 Codex 私有协议兼容。

目标约束见 [完整设计](full-upgrade-design.md)，提示词来源见 [适配记录](prompt-provenance.md)。
