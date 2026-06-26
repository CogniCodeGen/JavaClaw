# JavaClaw 智能体编排系统 — 设计文档

> 适用版本：JavaFX 25 + JDK 21 · AgentScope Java 1.0.11
> 最近更新：2026-06 · 配套阅读《[功能文档](./功能文档.md)》与仓库根 `CLAUDE.md`

---

## 1. 系统概述

JavaClaw 是一个多智能体桌面应用，核心是 **三种编排模式 + 一组可路由的领域专家 + 一套可自学习的技能体系**。架构遵循 **端口与适配** 原则：领域层（`agent` / `task` / `schedule`）只依赖 `api.*` 中的端口与 DTO，UI（JavaFX）作为适配实现挂在 `ui.javafx` 包下，因此领域逻辑与界面框架彻底解耦。

### 1.1 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│  UI 实现层 (ui.javafx / chat)                                  │
│  ChatViewController · SddTaskView · SkillCenterView ·          │
│  SettingsView · McpSettingsView · JfxUserInteractionPort       │
└───────────────────────────────┬──────────────────────────────┘
                                │ 依赖 api.* 端口与 DTO（不反向依赖 UI）
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  端口与抽象层 (api)                                            │
│  api.conversation: Mode / ModeRegistry / ConversationRequest   │
│                    ConversationCallbacks / ConversationEvent    │
│  api.interaction:  UserInteractionPort (confirm/confirmEx/notify)│
└───────────────────────────────┬──────────────────────────────┘
                                │ 模式注册 / 回调驱动
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  模式适配层 (mode)                                            │
│  ChatMode → ChatService   PlanMode → PlanModeService           │
│  TaskMode → SddTaskManager (ActionMode)                        │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  智能体编排层 (agent / task.sdd)                              │
│  AgentRuntime（基础设施容器：ModelFactory / TokenTracker /      │
│   MemoryManager / ExpertManager / KnowledgeExpert /            │
│   McpClientManager / VisionPreprocessor / BrowserManager）     │
│  ChatService（GEPA 闭环）· PlanModeService（MsgHub）·          │
│  SddOrchestrator（OpenSpec 六阶段）                            │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  模型与传输层 (agent.model)                                   │
│  ModelFactory：共享 JdkHttpTransport + 独立 ChatModel 实例     │
│  Provider: OpenAI / DashScope / Anthropic / Gemini / Ollama    │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 应用启动序列（`JavaClawApp.start()`）

```
WorkspaceManager 初始化（最先：确定当前工作区路径）
    → AgentConfig / EmailConfig / NotificationConfig 等单例加载
    → AgentRuntime 构建（基础设施容器，依赖注入）
    → ModeRegistry 注册三种模式（ChatMode / PlanMode / TaskMode）
    → ChatViewController 构建 UI 并绑定端口
stop() 时按相反顺序优雅关闭（浏览器、MCP 连接、线程池）。
```

---

## 2. 三种编排模式

UI 通过 `ModeRegistry.list()` / `listByPlacement()` 决定渲染位置，新增模式只需实现 `Mode` 接口并注册，UI 无需改造。

| 模式 | 入口服务 | 协作范式 | 适用场景 |
|------|----------|----------|----------|
| **普通模式** ChatMode | `ChatService.streamChat()` | 主编排器**层级委派**子智能体（子智能体互不可见） | 日常对话、工具型任务 |
| **规划模式** PlanMode | `PlanModeService.planChat()` | 基于 MsgHub 的**对等广播**（参与者互见） | 方案讨论、多角度分析 |
| **托管任务** TaskMode | `SddTaskManager` → `SddOrchestrator` | 确定性编排器按 **OpenSpec 生命周期**推进六阶段 | 长时、可验收的工程任务 |

---

## 3. 普通模式 — ChatService 与 GEPA 闭环

`ChatService` 维护主 ReActAgent（编排器）+ PlanNotebook，承载 **GEPA 五段闭环**：

```
用户输入
   │
   ▼ VisionPreprocessor：图片附件 → 文字描述（下游只见纯文本，避免编排器视觉分心）
   │
   ▼ GoalManager（Goal）
   │   单次轻量模型调用，将请求拆解为 1–4 个可验证目标 +
   │   结构化 SuccessCriterion（5 类谓词：artifact_exists / command_exit_zero /
   │   output_contains / external_check / freeform）+ 自然语言总结，注入系统提示词。
   │   短请求（<50 字）或失败时跳过；会话级 LRU 缓存（容量 16 / TTL 10 分钟）。
   │
   ▼ ToolRouter
   │   执行前用单次轻量模型调用，把用户意图路由到所需工具/技能/MCP 子集，
   │   仅注入命中的 schema 降低上下文 token；路由失败静默回退全量加载。
   │
   ▼ 主 ReActAgent（编排器）
   │   按意图选择 SubAgentTool 委派给子智能体，子智能体内部各自迭代 ReAct。
   │
   ├─▶ ExecutionMonitor（Execution）
   │     追踪工具调用轨迹，区分 FailureKind（NONE=成功 / TOOL_ERROR /
   │     TIMEOUT / EMPTY_RESULT / SAME_INPUT_LOOP）；
   │     连续失败 ≥2 触发 onConsecutiveFailure；同入参 ≥3 触发 onConvergenceStuck（收敛卡死）；
   │     维护滑窗成功率 successRate()。
   │
   ├─▶ EvaluationPipeline（Plan 评估）
   │     每 N 次工具调用间隔触发中段评估（目标完成度/结果完整度/效率三维打分）；
   │     ExecutionMonitor 在连续失败/收敛卡死时回调 forceEvaluate() 立即触发。
   │     分数低于阈值 → 触发计划修正。
   │
   └─▶ PlanEvolver（Adaptation + Critic）
         统一入口 evolve(EvolveRequest)，按 EvolveTrigger 三类选模板：
         EVAL_DRIVEN（评估驱动）/ CHALLENGE_DRIVEN / USER_CORRECTION（用户纠偏）。
   │
   ▼ StreamEventHandler
   │   将 Reactor Event 按类型路由（REASONING / TOOL_RESULT / HINT / AGENT_RESULT），
   │   区分主编排器与子智能体事件来源，转发到 ConversationCallbacks。
   │
   ▼ Platform.runLater → ChatViewController 流式气泡渲染
```

> 注：`gepa.eval.interval.tasks` / `gepa.eval.threshold` / `gepa.plan.adaptive.enabled` **仅作用于普通聊天模式**；SDD 托管任务系统不再读取这些键。

### 3.1 LoopDetectionHook — 循环检测

挂在编排器上的 Hook，基于字符相似度检测连续重复工具调用：相似度 ≥ 阈值则累计，达上限即中断 ReActAgent 迭代并注入系统提示。与 ExecutionMonitor 的 `SAME_INPUT_LOOP` 互补（前者看结果相似度，后者看入参重复）。

---

## 4. 规划模式 — PlanModeService

基于 AgentScope **MsgHub 对等广播**，区别于 ChatService 的层级委派：

```
协调者分析任务 → 选择相关专家加入 MsgHub
   → 多轮讨论（所有参与者能看到彼此发言）
   → 以 [PLAN_COMPLETE] 标记结束 → 协调者汇总方案
```

规划模式专家通过 `ExpertManager.createPlanModeAgents()` 用 MultiAgent 模型 + 规划后缀提示词重建；额外纳入纯推理的 KnowledgeExpert（不带 RAG 工具）。

---

## 5. 托管任务模式 — SDD 子系统

一个托管任务 ≈ 一个 **OpenSpec change**（落在 `{workDir}/.agent/openspec/changes/{slug}/`）。核心理念：**markdown 即真相**——spec 的 Given/When/Then 场景 = 验收谓词，tasks.md 复选框 = 步骤状态，无独立状态机；进度即 tasks.md 勾选折叠（`OpenSpecChange.progressPercent()`）。

### 5.1 OpenSpec 六阶段（`SddOrchestrator`，确定性、零 LLM 调用）

```
1. 提案 + 评审   clarifyAndPropose → Proposal(why/whatChanges)
                 → ReviewGate.reviewProposal（maxReviewRounds=3）
2. 规格          specify → Capability(Requirement + Given/When/Then Scenario)
3. 设计          design → 设计说明
4. 任务拆解+评审 planTasks → tasks.md(TaskItem 复选框) → ReviewGate.reviewPlan
5. 实现循环      取首个未勾 TaskItem → executeTask（过大项就地懒拆解）→ 勾选
                 → 循环至全勾（maxLoopIters=200 防失控）
6. 验收 + 补做   ScenarioVerifier.verifyAll 综合核验全部能力场景
                 ├─ 全过 → 归档完成
                 └─ 有未过 → remediate 按未过场景补做（追加任务、保留已完成）
                            受 maxReplanRounds=5 约束，超限 → NEEDS_HUMAN（绝不假完成）
```

**懒拆解**取代旧 FAST/SINGLE/MULTI 预分类——深度由实现循环遇到过大项时请求拆解长出来。

### 5.2 验证单点权威 — `ScenarioVerifier`

所有"是否完成"判定收敛到 `ScenarioVerifier`（**绝不默认放行**），取代旧 v5 的执行体自检 + fact-check + ChallengerAgent 三层。两粒度同机制：tasks.md 声明的文件存在性廉价自检 + 能力场景综合核验（注入的 `CommandRunner` 跑命令 + `CriticJudge` LLM 判定）。

### 5.3 端口注入与实现解耦

确定性编排器只依赖注入端口，模型/进程/JavaFX 实现挂在外层：

| 端口 | 默认/无头实现 | 生产实现 |
|------|--------------|----------|
| `SddAgents`（阶段智能体） | — | `AgentScopeSddAgents`（ReActAgent + 结构化输出 `SddDrafts`） |
| `CommandRunner`（跑命令） | — | `ProcessCommandRunner`（真实进程） |
| `CriticJudge`（LLM 判定） | — | `AgentScopeCriticJudge` |
| `ReviewGate`（人机评审） | `AutoApproveReviewGate` | `PortReviewGate`（经 `UserInteractionPort.confirm` 弹确认） |
| `SddProgress`（进度回调） | — | `SddTaskManager` → `SddTaskListener` → `SddTaskView` |

### 5.4 生命周期与恢复

- `SddTaskState` 7 态：PENDING / RUNNING / PAUSED / COMPLETED / NEEDS_HUMAN / FAILED / CANCELLED（管理器维度，与编排器内部阶段正交）。
- **启动恢复不自动续跑**：`SddTaskManager.recoverInterrupted` 一律把中断的 RUNNING 任务降为 PAUSED 等用户手动恢复（不自动烧 token）。续跑 = 读既有 change，从 tasks.md 首个未勾项继续。
- **任务级累计预算闸门**：`tokenBudget`≤0 不限；累计达限时编排器在阶段/循环边界停为 NEEDS_HUMAN，`updateTokenBudget` 调高后可续跑。
- **能力按需路由**：capabilities=auto 时复用 ToolRouter 裁剪能力工具集，保底 system+command，失败回退全量。

---

## 6. 子智能体（专家）体系

### 6.1 统一定义驱动

`ExpertManager` 用 `ExpertDef` 记录驱动专家创建，消除独立 Expert 类样板。扩展新专家只需在 `buildExpertDefs()` 加一行 `ExpertDef`。

### 6.2 内置专家清单

| 专家 | 工具名 | 能力组 | 类型 | 工具集 |
|------|--------|--------|------|--------|
| 编程专家 | `coding_expert` | coding | 纯推理 | 无 |
| 任务评估专家 | `task_evaluator` | evaluator | 纯推理 | 无 |
| 网页浏览专家 | `web_expert` | web | 带工具 | PlaywrightBrowserTools（导航/快照/交互/Cookie/PDF…） |
| 邮件专家 | `email_expert` | email | 带工具 | EmailTools（SMTP/IMAP） |
| 系统专家 | `system_expert` | system | 带工具 | SystemTools（鼠标/键盘/文件/截图） |
| 桌面自动化专家 | `desktop_expert` | desktop | 带工具 | DesktopTools（跨平台软件操作 + SoM） |
| 通知专家 | `notification_expert` | notification | 带工具 | NotificationTools（钉钉/微信/飞书/Webhook/邮件） |
| 命令行专家 | `command_expert` | command | 带工具 | CommandLineTools |

> KnowledgeExpert（知识/RAG 专家）独立于上表：普通模式承载知识库检索，规划模式以纯推理形态加入讨论。
> 自定义专家从配置加载（`CustomAgentConfig`），与内置专家统一注册为 `SubAgentTool`。

### 6.3 委派范式

- **普通模式**：编排器按意图选择 `SubAgentTool` 委派，子智能体内部独立迭代，事件经 `forwardEvents` 转发回编排器流。子智能体之间互不可见。
- **能力按需路由**：`DynamicTaskTool` 根据编排器指定的能力名（web/email/system/desktop/notification/command）从 `capabilityTools` 注册表按需组合工具集。

---

## 7. 模型与传输层

`ModelFactory` 创建 **共享 `JdkHttpTransport`** + 每个 Agent **独立 `ChatModel`** 实例（避免连接池耗尽）：

```
ModelFactory
 ├─ JdkHttpTransport（共享：HttpClient + 连接池 + 超时）
 ├─ UsageMeteredTransport（包装层，截获 prompt_tokens_details.cached_tokens
 │   回填 TokenTracker，AgentScope ChatUsage 不透传该字段）
 └─ createChatModel() 按 api.provider.type 分发
     ├─ OpenAI    → OpenAIChatModel + ToolSchemaFixFormatter（GLM 兼容修复）
     ├─ DashScope → DashScopeChatModel
     ├─ Anthropic → AnthropicChatModel
     ├─ Gemini    → GeminiChatModel
     └─ Ollama    → OllamaChatModel
```

`TokenTracker` 按会话/日期追踪用量，持久化 `token-usage.json`，经 `PricingTable` 估算 RMB 成本；缓存命中率 = cachedInput/meteredInput（与 input/output 是两套口径，勿混算）。

---

## 8. 技能自学习闭环

技能 = 智能体的程序性记忆，可由 agent 自己创建与进化（借鉴 NousResearch/hermes-agent）。技能本体与版本历史 **全局**（`{user.dir}/skills/`），使用统计与提案队列按 **工作区** 隔离。

```
三级渐进加载：
  L0 buildSkillCatalogPrompt — 常驻目录（名称/分类/标签/references 清单 + 沉淀 nudge）
  L1 buildSkillDetail        — skill_read 拉单技能全文
  L2 buildReferenceDetail    — skill_read(name, path) 拉单个参考文档

双轨产生提案，统一经 SkillProposalQueue 指纹去重：
  ① agent 主动调 skill_manage 工具（skill_create/patch/edit/delete/write_file…，受 nudge 引导）
  ② SkillCurator 被动蒸馏（轮结束 distillFromChatTurn / SDD 任务终态 distillFromSddTask）

skill.evolution.mode 总闸（off / suggest / auto，默认 suggest）：
  off    → 拒绝写入且不蒸馏
  suggest→ 提案入队待人工审阅
  auto   → 直落盘 + Toast，但 user-modified 技能强制降级为提案（绝不静默覆盖）
```

**JShell 执行**：`JShellTools`（`jshell_exec` 任意片段 / `jshell_run_script` 运行技能脚本）每次独立 JShell 实例（远程引擎=独立 JVM 进程隔离），超时 + 输出截断。**风险等级须保持 CONFIRM**，不可降级（否则 skill_write_file→jshell 链构成无人工干预的任意代码执行）。

---

## 9. 高风险工具确认机制

`ToolConfirmationManager` 通过 `UserInteractionPort` 在高风险工具执行前请求确认，查询 `ToolRiskRegistry` 决定 UI 形式：

| 风险等级 | UI 形式 | 行为 |
|----------|---------|------|
| `NOTIFY` | 非阻塞 Toast | 自动放行 |
| `CONFIRM` | 弹窗确认 | 等用户决策（60s 超时拒绝；托管场景 600s） |
| `DOUBLE_CONFIRM` | 关键词二次输入 | 关键操作 |

`ConfirmDecision` 三态：DENY / ALLOW_ONCE / ALLOW_ALL（"本次允许 / 永久允许"语义）。

托管任务内两道免人工通道：① `ReadOnlyCommands` 确定性只读命令白名单（ls/cat/grep…直接放行，不耗 LLM）；② `LlmToolScopeAssessor` 轻量模型范围评估 + `anyPathEscapes` 确定性路径校验（影响范围限于 workDir 才放行）。

---

## 10. 异步模型与线程安全

```
┌─────────────────────────────────────┐
│   JavaFX Application Thread          │  ← 所有 UI 更新经 Platform.runLater() 回到此线程
│   气泡渲染 / 回调执行 / 确认弹窗      │
└──────────────┬──────────────────────┘
               │ 回调跨线程投递
┌──────────────▼──────────────────────┐
│   Schedulers.boundedElastic() 线程池 │  ← Agent 推理 / HTTP / 工具执行 / SDD 装配
│   流取消经 Disposable.dispose()       │
│   util.AtomicDisposable 安全替换订阅  │
└─────────────────────────────────────┘
```

- 领域层不直接依赖 JavaFX，经 `UserInteractionPort` 抽象用户交互。
- `util.DebouncedPersister` 防抖持久化，避免流式更新中的高频磁盘 I/O。
- 定时任务用独立 `ScheduledTaskAgent` 执行，**绝不复用交互 ChatService**（并发会 dispose 订阅致聊天卡死）。

---

## 11. 工作区隔离

所有配置、数据、日志、浏览器 Cookie 按工作区隔离。`WorkspaceManager` 应用启动时最先初始化，所有 Config 单例与 DataManager 通过它获取当前工作区路径。

切换工作区时：重新加载配置 → `ModeRegistry.reload()` 让所有模式重建依赖 → 刷新 UI → 各工作区维度组件（SkillUsageTracker / SkillProposalQueue / ThemeManager 等）调 `reload()`。

详见 `CLAUDE.md` 的「Workspace Structure」。

---

## 12. 配置体系 — 易踩坑项

> 绝大多数属性可由 `AgentConfig.get(...)` 现场看出语义。仅列默认值或闸门关系不直观的：

| 配置键 | 说明 |
|--------|------|
| `api.provider.type` | 固定 OpenAI/DashScope/Anthropic/Gemini/Ollama；新增需在 ModelFactory 加分支 |
| `model.thinking.enabled` | 仅对支持思考模式的模型生效（OpenAI 兼容端启用会被忽略） |
| `gepa.eval.interval.tasks` / `gepa.eval.threshold` / `gepa.plan.adaptive.enabled` | 仅普通聊天模式；SDD 不读取 |
| `task.sdd.exec.timeout.seconds`（默认 900） | 覆盖单次 executeTask 全程；单项超时自动重试 1 次仍失败停 NEEDS_HUMAN |
| `task.sdd.exec.max.iters`（默认 12） | 单次 executeTask 的 ReAct 迭代上限 |
| `skill.evolution.mode`（默认 suggest） | 技能自学习总闸；auto 也不无条件直落盘（user-modified 强制降级提案） |
| `jshell_exec` / `jshell_run_script` | ToolRiskRegistry 必须保持 CONFIRM，不可降为 NOTIFY |
| `rag.embedding.dimensions` | 须与嵌入模型实际维度一致；切换模型需清空 `data/knowledge/` |

---

## 13. 扩展指南

| 扩展点 | 步骤 |
|--------|------|
| **新子智能体** | 创建 Expert 配置 → `ExpertManager.buildExpertDefs()` 加一行 ExpertDef → SubAgentTool 自动注册 |
| **新工具** | 对应 Tools 类加 `@Tool` 方法，返回 `ToolResponse.success()/error()/timeout()`（格式 `[toolName][状态] 消息`）；高风险在 `ToolRiskRegistry` 注册等级 |
| **新技能** | `skills/{id}/SKILL.md`（frontmatter）+ 可选 scripts/references/assets；或经 skill_create / 待审提案采纳沉淀 |
| **新交互模式** | 实现 `Mode` 接口并注册到 `ModeRegistry`，UI 经 list() 自动渲染 |
| **新 UI 框架** | 重写 `api.*` 端口的适配实现，不动领域代码 |
| **新主题** | chat.css 复制一个 `.theme-{id}` 块 + `ThemeManager.THEMES` 加一项（禁止硬编码颜色，引用 `-jc-*` 令牌） |

---

## 14. 关键源文件索引

| 文件 | 职责 |
|------|------|
| `app/JavaClawApp.java` · `app/Launcher.java` | 应用入口；Launcher 绕过 JavaFX 非模块化启动限制 |
| `agent/AgentRuntime.java` | 基础设施容器，依赖注入与共享工具 |
| `agent/ChatService.java` | 普通聊天编排器，GEPA 闭环 |
| `agent/PlanModeService.java` | 规划模式编排（MsgHub 对等广播） |
| `agent/expert/ExpertManager.java` | 专家定义驱动创建（内置 + 自定义） |
| `agent/router/ToolRouter.java` | 执行前工具/技能/MCP 路由 |
| `agent/goal/GoalManager.java` | 目标拆解 + SuccessCriterion |
| `agent/execution/ExecutionMonitor.java` | 轨迹追踪、FailureKind、收敛/失败检测 |
| `agent/evaluation/EvaluationPipeline.java` | 中段评估 |
| `agent/planning/PlanEvolver.java` | 统一计划演进入口 |
| `agent/model/ModelFactory.java` | 共享传输层、多提供商模型创建 |
| `agent/handler/StreamEventHandler.java` | 事件分类、子智能体事件解包、回调路由 |
| `agent/ToolConfirmationManager.java` · `ToolRiskRegistry.java` | 高风险工具确认 |
| `task/sdd/SddOrchestrator.java` | OpenSpec 六阶段确定性编排器 |
| `task/sdd/verify/ScenarioVerifier.java` | 验证单点权威 |
| `task/sdd/run/SddTaskManager.java` | SDD 任务生命周期与持久化 |
| `skill/SkillManager.java` · `skill/curation/SkillCurator.java` | 技能加载/版本管理 · 自学习蒸馏 |
| `chat/ChatViewController.java` | 普通模式 UI、回调注册与气泡渲染 |
| `ui/javafx/JfxUserInteractionPort.java` | UserInteractionPort 的 JavaFX 实现 |
| `config/AgentConfig.java` | 配置管理与系统提示词定义 |
</content>
</invoke>
