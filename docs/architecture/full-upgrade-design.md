# JavaClaw 4.0：旧能力补全、原 UI 与提示词体系

设计基线：2026-08-28；旧 UI 提交：`509f197`；Codex 公开参考提交：
`6478a751fde8884b2fdc76486fe23175a8e795d4`。

本文冻结目标与实施契约，不把设计当成已交付能力。当前实现、缺口与验证范围见
[能力验收矩阵](upgrade-acceptance.md)；提示词逐项来源见 [来源与适配说明](prompt-provenance.md)。
本轮只改源码、项目规范和测试数据，不读取、迁移或修改用户 3.x 数据。

## 1. 已确认决策

| 领域 | 决策 |
|---|---|
| UI | 沿用原 JavaFX/FXML/CSS、九套主题、布局和交互；架构升级不是产品改版 |
| 构建 | 保留九个领域模块，不为 Prompt、OCR、评估再拆模块 |
| 运行时 | 唯一 Thread → Turn → Item；不恢复旧 Session/Conversation/Run、Callback 或 Spring 工作区 Context |
| 接入 | 本机单用户 SDK/App Server；不提供嵌入式 Runtime、远程 RPC 或多租户 |
| 数据 | H2 v4 权威；JSON-RPC v1 兼容扩展；历史 migration 和既有兼容 fixture 不改写 |
| 模型 | OpenAI、Anthropic、Google；此次不改默认模型；Ollama/Deliverance/本地模型资产继续延后 |
| 桌面 Agent | 不恢复宿主机鼠标、键盘、截图、剪贴板自动化；桌面自身正常输入和附件选择不受此限制 |
| 通信 | 邮件、通知统一外部 MCP；不恢复内置 SMTP/IMAP/Webhook |
| MCP | 只支持 2026-07-28，不自动降级；资源与 Prompt 显式工具化 |
| 网络 | 默认拒绝私网；显式授权准确绑定端点、用途、工作区、期限和版本，不允许云元数据及控制通道 |
| Browser | 受控网页操作、人工登录、SecretRef 安全填充、加密会话；不开放任意 JS 或 Cookie 导出 |
| Memory | 低风险且有来源的内容可自动提取；冲突、固定条目、Persona 修改需要确认 |
| Skill | H2 权威，显式导入导出；学习模式关闭/建议/自动，默认建议；脚本和权限变更需确认 |
| SDD | H2 保存规格版本与状态，OpenSpec 仅为显式交换格式 |
| 无人值守 | 有范围、额度、有效期和版本绑定的预授权；永远不得获得 HOST_FULL_ACCESS |
| Loop | 预算 0 继承有限上限，不表示无限；耗尽保存检查点，不宣称成功 |
| 提示词 | 固定行为底座；人设、业务约定与回复风格可编辑且版本化 |
| AGENTS.md | 用户显式上传、预览、确认后导入 H2；无文件监听、逐轮读取和自动覆盖 |

## 2. 架构与所有权

| 模块 | 职责及允许的 JavaClaw 生产依赖 |
|---|---|
| javaclaw-api | 不可变领域值、模型/工具数据和 Sandbox 契约；无其他模块依赖 |
| javaclaw-protocol | JSON-RPC、Wire DTO、Schema、Mux；无其他模块依赖 |
| javaclaw-agent-runtime | Kernel、Turn 调度、工具治理、Conversation/Automation/Knowledge、Prompt；依赖 api |
| javaclaw-app-server | Graph、RPC Handler、H2、模型适配、扩展、Broker；依赖 api/protocol/agent-runtime |
| javaclaw-native-hosts | FFM、Sandbox Launcher、Windows Transport Host；依赖 api/protocol |
| javaclaw-browser-service | 独立 Playwright 进程；依赖 protocol |
| javaclaw-client | SDK 领域客户端及 CLI；依赖 protocol |
| javaclaw-desktop | SDK-only JavaFX 客户端；依赖 client |
| javaclaw-packaging | 装配 Server/Native/Browser/Client/Desktop 发行物 |

调用顺序固定为：

```text
Desktop / CLI → SDK 领域客户端 → SDK 内部 Protocol Mapper → JSON-RPC
→ AppServerSession / RpcRouter → 领域 RpcHandler → UseCase
→ Runtime / 领域服务 → Repository / Model / Extension Adapter

AgentLoopKernel → TurnToolSession → GovernedToolRuntime
→ Schema / 来源 / Hook / Approval / Policy → ToolHandler
→ Sandbox / NetworkBroker → Item / Event / Outbox

ModelInvocationService → PromptCompiler + ContextAssembler → ModelGateway
```

设置保存、资源查询、表单校验直接使用 UseCase，不多调用一次模型。
Kernel/Runtime/Prompt 禁止 Spring、JavaFX、JDBC、H2、Protocol、MCP Wire 和 Jackson。
JSON Schema 解码集中于 Tool 边界；Quartz 仅在 Automation，POI/PDFBox 仅在 Knowledge。
Desktop/CLI 不导入 Protocol/Jackson。业务服务不返回 Wire/JsonNode。
FFM 模块名保持 `com.javaclaw.nativehosts`；合并 JAR 不合并独立进程和权限上下文。

## 3. 执行、预算与恢复

### 3.1 单一执行上下文

`TurnScope` 显式携带取消、墙钟截止时间和 `BudgetAccount`；不使用 ThreadLocal。
主模型、结构化修复、评估、OCR、MCP sampling 和子智能体都必须记账。
一次模型调用在发送之前占用额度；失败的请求也占用调用次数。未知费用采用保守预算扣减，
不伪装成 Provider 报告的实际计费。输出 token 上限不能超过已预留余额。

一个 Thread 至多一个活动 Turn，每 Workspace 最多八个，每父 Turn 最多四个子智能体。
子任务从父账户预留预算；当前分配策略最多取父剩余额度的一半，同时受子 Profile 更低上限限制，
为父任务保留收尾空间。取消后未退出模型/子任务的预留额度不得提前重新分配。
父 Turn 结束时撤销未收回子任务的继续执行权。对话 fork 是独立用户任务，不继承父取消生命周期。
等待使用虚拟线程，不占用会造成父子互相饥饿的固定 Agent 执行池。

### 3.2 领域执行状态

后续业务闭环共用以下契约，不再创建另一套 Runtime：

- `ExecutionPolicy`：已解析的权限和资源上限。
- `StepIntent` / `StepCheckpoint`：步骤、输入版本、已确认结果及恢复位置。
- `EvaluationService`：确定性证据优先，模型评估补充，使用场景化 ResponseContract。
- `EffectReceipt`：副作用的幂等键、意图、执行凭据及 UNKNOWN/CONFIRMED 状态。
- `CapabilityState`：AVAILABLE、NOT_CONFIGURED、UNSUPPORTED、BLOCKED_BY_POLICY、DEGRADED。

这些契约中尚未进入生产的部分必须留在验收矩阵，不能只添加空接口便标记完成。

### 3.3 崩溃与重复副作用

崩溃后非终态 Turn 变为 INTERRUPTED，未完成 Item 变为 FAILED；恢复创建新 Turn。
只读操作可以有限重试；支持幂等的远端写操作复用原键。
已确认发送、提交、删除不重放。执行结果未知时先查询或请求确认，禁止自动重新发送。
预算耗尽不等于完成，必须保存进展、剩余工作和恢复条件。
Checkpoint、Item、状态、Event、Outbox 必须在同一 H2 事务内提交；外部网络不放进数据库事务。

## 4. 旧能力的目标闭环

### Conversation / Agent Studio / Plan

Chat 对应 Thread；恢复原历史列表、搜索、消息卡片、工具进度、附件、重试、分支和 usage。
Agent 定义对应版本化 Profile。提示词编辑不覆盖固定底座；工具选择不扩大权限。
PLAN 输出结构化目标、范围、步骤、依赖、验收、风险、待决策项；经 Schema 校验。
“继续”不能改变 PLAN 权限。采用计划是显式操作，固化所采用版本后进入普通执行 Turn。
只读沙箱不足以禁止邮件等远端写入：PLAN 工具目录必须排除未经第一方证明为只读的业务能力。
外部 MCP 的 readOnlyHint、低风险评级或签名不能自动成为只读证明。

### Loop / Workflow / SDD / Schedule

| 能力 | 执行策略 | 必须可验证的结果 |
|---|---|---|
| Loop | 一个有限 Turn，默认新建 25 次迭代、1 小时、100 次模型调用、200,000 token；取 Profile 更低上限 | 目标与成功准则、进展增量、无进展停止、Evaluation、Checkpoint |
| Workflow | 有界有向图；START/END/AGENT/TOOL/CONDITION/TRANSFORM/HUMAN_INPUT/OUTPUT | 节点输入输出校验、循环上限、子 Thread 结果、确定性节点 Item、暂停恢复 |
| SDD | 提案→规格→设计→任务→实现→核验→补做→归档的版本化状态机 | 规格绑定审批、实际测试/退出码/业务结果、变更后评审失效、OpenSpec 导入导出 |
| Schedule | H2 权威，Quartz RAMJobStore 只触发；稳定 Thread，每次新 Turn | 幂等触发、SKIP 重叠、不补跑 misfire、预授权撤销、取消与诊断 |

旧配置不自动放大。0 在解析时继承有限默认，原保存值仍保留。
Workflow 表达式不是主 JVM 任意代码；循环必须显式有上限。
不能用文件存在、代码关键词、Markdown 哨兵或模型自述替代完成证据。
目前普通模型循环与保存定义只算基础设施，不等于上述 Loop/Workflow/SDD 闭环。

### Memory / Knowledge / Skill

Memory 覆盖事实、情景、实体关系、纠错、Persona、固定条目、历史和恢复。
提取依据用户或工具证据，不把助手建议当事实；单次提问不推断长期兴趣或敏感属性。
冲突、固定内容与 Persona 修改需确认；维护任务低优先级、有限预算，不递归触发自身。

Knowledge 支持 PDF/TXT/Markdown/CSV/JSON/XML/HTML/DOCX，宏和外部实体不得执行。
文档、Chunk、Embedding 来源与版本留在 H2；向量索引只作可重建投影。
新 generation 完成后原子切换，不先清空可用索引；无 Embedding 时明确关键词降级。
检索优先使用当前 Turn 输入，记录实际引用的条目、版本与摘要。

Skill 先给目录，选中后读取完整指令及必要资源，禁止全量正文注入 SYSTEM。
执行前复核 enabled/revision；超出预算不静默截断后继续执行。
恢复 Bundle、脚本、版本、学习提案与回滚。自动学习只针对低风险、已验证、未被用户修改的内容。
脚本新增、权限扩大、覆盖用户内容必须确认；相似内容优先生成更新提案，不反复创建近似技能。

### 文件、代码、媒体与附件

文件读写、补丁、命令、PTY、JShell、脚本、系统摘要统一经工具治理。
JShell 必须在沙箱子 JVM；文档二进制解析在受限 Worker，不在 App Server 中执行复杂不可信解析。
附件仅以 SHA-256 引用进入客户端请求；服务端验证大小、MIME、内容哈希后发送真实内容。
图片不退化为文件名文本；OCR 共享模型预算，单次最多二十页且保留页码与不确定性。
未接入 Worker 的格式显式报告不可用；不虚构成功，也不新增无旧能力依据的音视频生成系统。

### Browser、站点与外部通信

Browser 操作包括导航、快照、定位、点击、填充、等待、标签页、上传下载、截图和 PDF。
引用基于当前页面状态，变化后重新获取。人工登录由用户在可见浏览器完成。
凭据通过 SecretRef 注入，不进入 Prompt、工具结果和诊断；会话加密且绑定站点与授权范围。
不提供任意 JS、原始 Cookie 导出或读取敏感字段。driver/Chromium 纳入同一沙箱进程树。

邮件和通知只使用真实发现的外部 MCP 描述符。预授权绑定连接、工具、接收对象、额度、期限、
Schema/revision；版本改变即失效。“已提交”不能被扩写成“已送达”。
未配置支持新协议的服务时，展示未配置/不支持，不展示可点击但必然失败的内置发送按钮。

### 协作与工作树

通过 CollaborationGateway 完成 spawn/steer/wait/cancel/diff/apply/cleanup。
Git 写任务使用 detached worktree 和临时 index，合成基线包含 tracked 与非 ignored untracked。
父工作区真实 index 保持不变；无法无损快照就拒绝写子任务，允许只读。
相对基线生成有界 binary patch，父 Turn 明确审批后应用；冲突保留备份、信息和 worktree。
成功可清理，取消/崩溃/冲突需提供显式恢复或删除。非 Git 工作区只有一个写者。

## 5. 提示词体系

### 5.1 职责与分层

PromptTemplate/Catalog 管理随代码发布的正文、版本和哈希；PromptCompiler 是纯编译器；
ContextBlock 标识资料来源与可信度；PromptSnapshot 记录实际调用的模板、配置和引用；
ResponseContract 负责真正的结构校验。运行时不从 GitHub 下载模板。

| 层 | 输入 | 编辑与信任规则 |
|---|---|---|
| 固定底座 | 范围、工具纪律、证据、诚实、资料不是授权 | 不可由 Profile 替换 |
| 当前模式 | CHAT/PLAN/LOOP/WORKFLOW/SDD/SCHEDULE/SUBAGENT | 由服务端已解析配置决定 |
| 人设 / AGENTS.md | Profile 可编辑原文；全局与项目目录链指令 | 人设不覆盖固定底座；更深目录指令后出现、优先级更高；不扩大权限 |
| 能力 | 当前 TurnToolSession、审批、沙箱、预算、状态 | 由运行时生成，不由提示词虚构 |
| 按需 Skill | 选中的完整技能及必要参考 | 治理读取，执行复核版本和权限 |
| 任务/参考 | 当前用户输入、历史、Memory、Knowledge、网页、摘要、工具输出 | 带来源的数据；不升级成 SYSTEM |
| 输出契约 | Plan/Evaluation/Proposal/PromptDraft 等 | 实际 Schema 校验，最多一次有预算的修复 |

正文分隔符不是安全边界。消息角色、标签、服务端授权、独立进程和攻击测试共同约束行为。
当前明确用户要求可以覆盖默认语气，不能改变代码权限。影响业务结果的真实冲突走用户输入流程。
只保留适合展示的 reasoning summary，不索要或持久化内部思维链。

### 5.2 场景与完成证据

内置目录覆盖 base、coding、chat、plan、loop、evaluation、workflow、sdd、schedule、subagent、
compaction、summary_prefix、memory、memory_consistency、skill、skill_usage、review、browser、ocr、
prompt_optimize、mcp_sampling。目录存在不代表每个业务场景已接入生产。

固定底座要求先区分解释/分析/规划/执行；检查可查证事实；只在已授权范围行动；
只用实际工具；区分完成/提交/待审批/等待/未验证/失败；预算不足如实交接。
coding 片段特别规定保留用户修改、项目格式与注释、原有 UI，并如实说明测试覆盖。
摘要压缩交接目标、决策、限制、已完成、剩余和关键引用；原生 OpenAI 压缩则保存并原样重放
Responses canonical output。两条路径都不删除 Thread 原始证据。
评估和审查要求可复现、具体、有位置/影响/严重程度的证据，允许无问题或无新增。
MCP sampling 是无工具、有限预算、隔离历史的外部请求；其 systemPrompt 作为资料而非授权。

### 5.3 AGENTS.md 项目指令

全局目录按 `AGENTS.override.md` 、`AGENTS.md` 选择首个非空文件。项目层从 Thread 实际工作目录向上
寻找最近根标记，再按项目根到工作目录遍历；每层依次选择 override、标准文件和配置 fallback，
每层最多一份。默认根标记为 `.git`，项目总预算 32768 字节；全局文件不计入该预算。
无根标记时只读取工作目录，不越过工作区或 Turn 可读根。不可读文件与解析后越界符号链接阻止调用；
超过预算则截断并警告。每个 Thread 按工作目录、可读根与配置修订缓存，普通文件变更不热更新。
有效正文作为 USER 片段注入；PromptSnapshot 只存 scope、绝对路径、SHA-256、字节数和截断状态。
管理页及 `workspace/instructions/resolve` 只读显示解析状态，不返回或保存第二份正文。

### 5.4 Agent Studio 草稿优化

预览不调用模型。优化创建独立、有限、无工具 Turn，输入仅名称、用途、草稿、实际能力目录。
不继承主任务 Memory、私密规则和完整历史，不读取凭据，也不自动启用能力。
结果为 PromptDraft（目标 Profile/revision、建议正文、变化、警告），不是一次 Profile 更新。
原界面展示预览/差异，用户采用到编辑区后仍需显式保存。
优化期间编辑已改变或服务端 revision 变化时拒绝覆盖。
幂等重放返回原优化 Turn，不能重复计费。自定义正文原样保留，不正则清理或静默升级。

### 5.5 追踪与评估

模板清单随发行物保存 id/version/SHA-256；H2 只保存不可变运行档案。
每次模型调用记录用途、实际模板/工具/资料版本、编译哈希及预算消耗。
默认诊断只含标识、版本、哈希与统计；不导出完整私密上下文。
已被引用的历史版本不能提前回收。同 id/version 不同内容必须拒绝启动或明确版本升级。

普通 PR 使用假服务验证装配、工具选择边界、结构输出、取消、预算和注入攻击。
真实三家模型对照实验是单独、显式授权、有限费用的工作；使用合成数据，记录完成率、
错误工具调用、结构有效率、输入 token 与延迟。没有真实实验，不宣称模型效果已经提升。

## 6. 数据与协议扩展

| Migration | 目标领域 | 当前状态 |
|---|---|---|
| V001 / V002 | 现有 v4 与工作树 | 保留原文件、原校验和 |
| V003 | 自动化版本、步骤、检查点、预算、EffectReceipt、SDD 状态 | 已接入 H2 与领域执行 |
| V004 | Memory/Persona/关系、Knowledge/索引 generation | 已接入版本、确认及 generation 切换 |
| V005 | Skill 版本、资源、学习提案 | 已接入学习、审阅、Bundle 和回滚 |
| V006 | 加密 Browser 会话、站点、网络/通信预授权 | 已接入 Browser 与受范围限制的授权 |
| V007 | 历史项目规则表、模板档案、Turn 调用快照 | 模板档案与调用快照仍使用；旧规则表由 V009 删除 |
| V008 | 维护 inbox、学习设置与低优先级调度 | 增量补充，不递归创建维护任务 |
| V009 | ConversationWindow；删除旧项目规则表 | 备份后迁移；旧 compaction 摘要回填，新 Item 仅保留 id |

H2 DDL 不假定整批可回滚。实际部署前备份 v4 根，测试旧 v4→新版本，不触碰 3.x。
已有 Profile/Credential/Attachment 继续复用。附件引用回收和历史快照保留需一起验收。

JSON-RPC v1 保持既有方法并扩展：

| 方法 | 语义 |
|---|---|
| profile/prompt/preview | 不计费的层、版本、能力匹配预览 |
| profile/prompt/optimize | 幂等创建优化 Turn，返回引用，不保存 Profile |
| workspace/instructions/resolve | 只读返回工作目录、来源元数据、警告与项目字节数；不返回正文 |
| thread/compact/start | 立即返回空结果；后续使用普通 Turn/Item 事件报告压缩生命周期 |

profile.systemPrompt 仍兼容，但明确只代表可编辑层。
SDK 保留八个领域客户端；公共签名仅 JDK/SDK 类型，新增 PromptPreview、PromptDraft、
AgentsInstructionResolutionInfo 和 typed Plan，不开放 raw RPC 或 Wire/JsonNode。
其余节点、规格、检查点、版本、站点和通信接口按领域增量补齐，不新建通用超级接口。

## 7. 原 UI 恢复策略

以 509f197 的 CSS 令牌、主题、FXML 层级和交互为基准，控制器通过 DesktopComponentGraph
及 FXMLLoader controller factory 注入 SDK ViewModel。后台虚拟线程，所有属性更新由 FX Dispatcher 派发。
恢复原侧栏/工作区/搜索/会话导航/标题，原消息与工具/计划/进度展示、输入区与模式切换，
再逐页恢复 Agent Studio、Memory、Knowledge、Skill、Workflow、SDD、Schedule、Plugin、MCP、站点。
Prompt 预览/优化和只读 AGENTS.md 状态页复用原表单与分栏样式，不新增不同视觉语言的管理后台。
慢 delta 采用有界内存与合并 UI 派发；最终持久内容优先，重复/乱序/过期选择不能污染当前会话。

每个页面必须同时有结构/交互测试和真实窗口基线。加载成功、按钮存在、能列出记录不代表完整旧能力恢复。

## 8. 实施与发布门禁

按 P0 基线→P1 UI 外壳→P2 内核与 Prompt→P3 执行能力→P4 自动化→P5 知识→
P6 Browser/通信/协作→P7 全客户端闭环→P8 清理发行推进。
每个能力先增加等价/攻击测试，再迁移实现，最后删除旧链路。不能用空按钮、固定成功返回或保存定义替代完成。

验证至少包含：

- 协议 golden、未知 Item、幂等、revision、断线恢复、1024 条/8 MiB 背压和 256 MiB 附件。
- H2 状态/Item/Event/Outbox 原子性、检查点/引用回收、崩溃恢复、历史 migration 不变。
- 单活动 Turn、子预算/配额、取消、审批、无重复副作用、结果 UNKNOWN 恢复。
- PLAN 越权、Prompt 注入、Secret 泄漏、Skill/MCP 权限撤销和结构化修复预算。
- Memory 事实来源、固定内容、Persona 冲突、Knowledge generation、Skill 回滚。
- 原 UI 页面、草稿确认、规则重导入、真实附件、重连和 App Server 重启。
- 沙箱路径/符号链接/reparse、网络、PTY/ConPTY、进程树、Named Pipe SID、FFM ABI。
- 同一 fake model/相同编译 Prompt 的 direct 与 SDK p50/p95 增量≤10%，500 慢订阅者内存有界。

开发门禁：

```bash
mvn spotless:apply
mvn spotless:check checkstyle:check
mvn clean -Djavaclaw.require.native.sandbox=true -Djavaclaw.performance.gate=true verify
```

macOS arm64/x64、Linux arm64/x64、Windows x64 使用原生 Runner；平台安全测试不得 skip。
同版本 jlink/jpackage 安装包、健康检查、SBOM、许可、SHA-256 与正式签名门禁全部通过才能发布。
当前机器的 macOS 结果不能代替其他平台，也不能代替真实模型评估和完整旧功能验收。
