# JavaClaw 智能体编排系统 — 设计文档

> 适用版本：JavaFX 25 + JDK 25 · AgentScope Java 1.0.12
> 最近更新：2026-07 · 配套阅读《[功能文档](./功能文档.md)》与仓库根 `CLAUDE.md`

---

## 1. 系统概述

JavaClaw 是一个多智能体桌面应用，核心是 **五种编排模式 + 一组可路由的领域专家 + 一套可自学习的技能体系 + 一个向量化长期记忆基座**。架构遵循 **端口与适配** 原则：领域层（`agent` / `loop` / `task` / `workflow` / `schedule`）只依赖 `api.*` 中的端口与 DTO，UI（JavaFX）作为适配实现挂在 `ui.javafx` 包下，因此领域逻辑与界面框架彻底解耦。

两条贯穿全项目的取向：① **确定性内核 + 注入端口**——各子系统把"判定/推进"做成零 LLM 的确定性引擎（`LoopController` / `SddOrchestrator` / `GraphEngine` / `CorrectionDetector`），模型与进程调用退到可替换端口，因此可离线单测；② **绝不默认放行**——完成与正确性判定需"提议 ∧ 客观核验"双点头，降级路径宁可保留原状（pending 暂存 / NEEDS_HUMAN / 拒绝执行）也不假装成功。

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
│  ChatMode → ChatService      PlanMode → PlanModeService         │
│  LoopMode → LoopService      WorkflowMode → WorkflowService     │
│  ShellMode → ShellCommandService                               │
│  TaskMode / WorkflowCenterMode（ActionMode，仅开视图）          │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  组合根 (runtime)                                             │
│  ApplicationKernel → RuntimeFactory → WorkspaceRuntime         │
│  （WorkspaceContext 不可变路径快照；工作区切换=整体重建）        │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  智能体编排层 (agent / loop / task.sdd / workflow)            │
│  AgentRuntime（基础设施容器：ModelFactory / TokenTracker /      │
│   MemoryManager / ExpertManager / KnowledgeExpert /            │
│   McpClientManager / VisionPreprocessor / BrowserManager）     │
│  ChatService（GEPA 闭环）· PlanModeService（MsgHub）·          │
│  LoopController（决策漏斗）· SddOrchestrator（OpenSpec 六阶段）│
│  GraphEngine（状态图引擎，四条内置路径亦跑在其上）             │
└───────────────────────────────┬──────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  记忆与持久化层 (memory / config)                             │
│  MemoryService：EclipseStore 对象图 + JVector 向量索引          │
│   （事实/情景/实体/人格/纠错/检查点）· EmbeddingGateway 熔断    │
│  AppDatabase：全局单文件 H2（结构化状态，workspace_id 隔离）    │
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
    → 全局交互端口 + PlaywrightBrowserManager（懒启动）
    → ApplicationKernel.initialize()
        → WorkspaceContext 捕获不可变路径快照
        → RuntimeFactory 事务式创建 WorkspaceRuntime
          （AgentRuntime + WorkflowService + Chat/Plan 服务
           + ModeRegistry：Chat/Plan/Loop/Workflow/Shell/Task/WorkflowCenter 七模式）
        → ScheduleManager / PluginManager / SddTaskManager 接管新运行时
    → ChatViewController 采用整个 WorkspaceRuntime 并构建 UI
stop() 统一委派 ApplicationKernel，按依赖反序关闭。

配置重建与工作区切换也只允许经过 `ApplicationKernel`。切换前先停靠 Schedule/Plugin/SDD，
失败时内核切回原工作区并重建；UI 不直接创建或销毁 AgentRuntime/ChatService。
```

---

## 2. 五种编排模式

UI 通过 `ModeRegistry.list()` / `listByPlacement()` 决定渲染位置，新增模式只需实现 `Mode` 接口并在 `RuntimeFactory` 注册，UI 无需改造。

| 模式 | 入口服务 | 协作范式 | 适用场景 |
|------|----------|----------|----------|
| **普通模式** ChatMode | `ChatService.streamChat()` | 主编排器**层级委派**子智能体（子智能体互不可见） | 日常对话、工具型任务 |
| **规划模式** PlanMode | `PlanModeService.planChat()` | 基于 MsgHub 的**对等广播**（参与者互见） | 方案讨论、多角度分析 |
| **循环模式** LoopMode | `LoopService` → `LoopController` | 确定性**决策漏斗**逐轮裁决完成/继续/停止，上下文接力 | 反复推进同一目标、轮询等待外部条件 |
| **托管任务** TaskMode | `SddTaskManager` → `SddOrchestrator` | 确定性编排器按 **OpenSpec 生命周期**推进六阶段 | 长时、可验收的工程任务 |
| **工作流模式** WorkflowMode | `WorkflowService` → `GraphEngine` | 用户显式编排的**状态图**顺序执行 + 逐节点检查点 | 固化复用已跑通的流程 |

**统一终态契约**：所有模式的 `start()` 返回 `ConversationHandle`，完成/取消/失败经 `ConversationCallbacks.onTerminal(ConversationOutcome)` 投递**恰好一次**（sealed 三态 Completed / Cancelled / Failed）。竞态由 `TerminalCallbackGuard`（终态后丢弃迟到事件）与 `SingleConversationRun`（单运行门禁）收敛，新增模式不必自己手写。

**内置路径也跑在图引擎上**：ChatService / PlanModeService / LoopService / SddTaskRunner 各持一个只读系统图（`SystemGraphFactory.chat()/plan()/loop()/sdd()`），经 `WorkflowService.runSystem(...)` 执行，领域管线以 SYSTEM 节点阶段挂入（`SystemPipeline` + `SystemPipelineAwaiter` 把异步回调管线适配成一个阻塞节点）。系统图只增加阶段可观测性与检查点，不改变原编排语义；**执行器不得把未执行阶段自行标记完成**。

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
   ▼ MemoryService.prepareCorrectionTurn（同步）
   │   识别显式纠错 → 本轮调用前撤销旧记忆并写入更正（详见 §13）。
   │
   ▼ MemoryService.recall(query)
   │   按当前问题向量召回：人格整段 + 相关纠错 + 事实 Top-K + 相关情景，
   │   按字符预算注入 <loaded_context>（取代旧的整文件灌入）。
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
   ▼ CorrectionGuard（仅相关纠错轮次）：缓冲最终回复，命中旧错误则换安全兜底文案
   │
   ▼ MemoryService.rememberTurn（异步）：落情景 + 蒸馏事实（详见 §13）
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

## 5. 循环模式 — LoopController 决策漏斗

`LoopService` 解析 `@loop` 指令 → `GoalManager` 把目标拆成 `SuccessCriterion` → 装配 `LoopController` 在 `boundedElastic` 上跑；runner 构造时绑定 `ToolCallOrigin.managedTask(loopId, workdir)`，循环结束清理任务白名单。单活跃循环（CAS 抢占），可与聊天并行。

### 5.1 每轮的决策漏斗（`decide()`，零 LLM）

```
① 轮前预算护栏   轮数 / token 用量 / 墙钟 / 取消 —— 别启动付不起的一轮
② 完成判定       执行体提议（loop_report 结构化汇报优先，哨兵行降级解析）
                 ∧ 客观核验（命令真跑 / 文件真查 / 输出对累计产出匹配）
                 —— 两者点头才算完成，freeform/external 类交模型验收员
③ 轮后护栏       连续失败（阈值 2，先重试一次）/ 连续无进展（阈值 2）
④ 继续           有剩余且"真前进"（见下）
⑤ 否则           判为收敛不了，停止
```

**进展必须有状态变化为证据**（`ProgressGate` 分层）：准则通过数创新高（高水位防"坏了又修好"刷假进展）＞ 工具调用指纹有新意（原样重放=复读机）＞ 自报"剩余"清单变化（连续两轮相同=自我供认卡住）＞ 输出新颖度（仅无准则目标兜底）。纯文本目标在确定性判停滞后，还会经 `CompletionJudge.progressMade` 做一次**停滞仲裁**，避免"持续打磨被误杀 / 换措辞复述被放行"。

### 5.2 轮次汇报协议与节奏

执行体每轮调 `loop_report`（常驻 `loop` 工具组，不参与路由裁剪）提交 done / summary / remaining / next_delay_seconds / reason。`next_delay_seconds>0` 即**声明式等待轮**（轮询语义：等 CI、等邮件），等待轮跳过停滞计数，由墙钟与轮数上限兜底。`Cadence` 两档：SELF_PACED（采纳自报延迟，有上限截断）与 INTERVAL（固定间隔，整体豁免停滞计数）。`CarryContext` 默认 SUMMARY 模式接力：历轮取 `loop_report` 的 summary（零额外模型调用）+ 末轮全文，上下文有界增长。

### 5.3 端口与安全

`LoopIterationRunner`（单轮执行，生产实现 `AgentScopeLoopRunner` 沿用 `ScheduledTaskAgent` 隔离范式）与 `CompletionJudge`（模型验收 + 停滞仲裁，含 `CONSERVATIVE_DENY` 保守空实现）是唯一的模型接缝，注入假实现即可离线单测（见 `LoopControllerTest`）。用量由控制器逐轮累加真实 `IterationResult`，**不读全局会话计数**（否则与聊天并行时会被污染）。command 类验证准则由 LLM 分解产出且执行不经工具确认，故非只读命令（`ReadOnlyCommands` 白名单外）在**启动前**须经用户一次性确认，拒绝则不启动循环。

---

## 6. 工作流引擎 — GraphEngine 与系统图

### 6.1 确定性图执行

`GraphEngine` 是**顺序、有界、可检查点**的执行器（不是并发调度器）：先 `GraphValidator.requireValid` 校验，逐节点执行、每节点前后 `store.checkpoint`，步数受 `GraphDefinition.maxSteps` 硬限；`CancellationToken` 同时承载取消与暂停。节点实现经 `NodeExecutorRegistry` / `NodeExecutor` / `NodeResult` 接缝注册（`BasicNodeExecutors` / `AgentNodeExecutor` / `ToolNodeExecutor` / `SystemPipelineNodeExecutor`），事件经 `GraphEvent` / `GraphListener` 外流。

中断续跑时 `ResumeSafety` 决定当前节点能否直接重跑：`CONFIRM_RETRY` 必须再经 `UserInteractionPort` 确认（有副作用的节点取此档），`SAFE` 才静默重试。

### 6.2 定义、存储与编辑

`GraphDefinition`（schemaVersion / nodes / edges / startNodeId / maxSteps）与 `NodeDefinition` / `EdgeDefinition` 全部不可变；`GraphKind` 分 SYSTEM（代码注册、只读）与 CUSTOM（可编辑发布）；`NodeType` 九型中 **SYSTEM 节点不出现在用户节点面板**。定义与运行状态落 H2 `workflow_definitions` / `workflow_threads` / `workflow_runs` / `workflow_checkpoints` 四表（工作区隔离）。`WorkflowEditorModel` 是 **JavaFX 无关**的编辑会话（100 步不可变快照撑 undo/redo），UI 为 `WorkflowCenterView`。

### 6.3 安全边界

`WorkflowToolGroupPolicy` 是白名单闸：自定义图节点只能声明本地工具组，**远程 `mcp` 组默认禁止**，未知/未授权组抛 `SecurityException`。放宽此白名单等价于一次授权决策，需慎重。

---

## 7. 托管任务模式 — SDD 子系统

一个托管任务 ≈ 一个 **OpenSpec change**（proposal / spec / tasks 的 markdown 原文落 H2 `sdd_spec_docs` 表并按 slug 归组，`work_dir` 只作项目目录标识；旧的 `{workDir}/.agent/openspec/` 文件布局已废弃）。核心理念：**markdown 即真相**——spec 的 Given/When/Then 场景 = 验收谓词，tasks.md 复选框 = 步骤状态，无独立状态机；进度即 tasks.md 勾选折叠（`OpenSpecChange.progressPercent()`）。

### 7.1 OpenSpec 六阶段（`SddOrchestrator`，确定性、零 LLM 调用）

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

### 7.2 验证单点权威 — `ScenarioVerifier`

所有"是否完成"判定收敛到 `ScenarioVerifier`（**绝不默认放行**），取代旧 v5 的执行体自检 + fact-check + ChallengerAgent 三层。两粒度同机制：tasks.md 声明的文件存在性廉价自检 + 能力场景综合核验（注入的 `CommandRunner` 跑命令 + `CriticJudge` LLM 判定）。

### 7.3 端口注入与实现解耦

确定性编排器只依赖注入端口，模型/进程/JavaFX 实现挂在外层：

| 端口 | 默认/无头实现 | 生产实现 |
|------|--------------|----------|
| `SddAgents`（阶段智能体） | — | `AgentScopeSddAgents`（ReActAgent + 结构化输出 `SddDrafts`） |
| `CommandRunner`（跑命令） | — | `ProcessCommandRunner`（真实进程） |
| `CriticJudge`（LLM 判定） | — | `AgentScopeCriticJudge` |
| `ReviewGate`（人机评审） | `AutoApproveReviewGate` | `PortReviewGate`（经 `UserInteractionPort.confirm` 弹确认） |
| `SddProgress`（进度回调） | — | `SddTaskManager` → `SddTaskListener` → `SddTaskView` |

### 7.4 生命周期与恢复

- `SddTaskState` 7 态：PENDING / RUNNING / PAUSED / COMPLETED / NEEDS_HUMAN / FAILED / CANCELLED（管理器维度，与编排器内部阶段正交）。
- **启动恢复不自动续跑**：`SddTaskManager.recoverInterrupted` 一律把中断的 RUNNING 任务降为 PAUSED 等用户手动恢复（不自动烧 token）。续跑 = 读既有 change，从 tasks.md 首个未勾项继续。
- **任务级累计预算闸门**：`tokenBudget`≤0 不限；累计达限时编排器在阶段/循环边界停为 NEEDS_HUMAN，`updateTokenBudget` 调高后可续跑。
- **能力按需路由**：capabilities=auto 时复用 ToolRouter 裁剪能力工具集，保底 system+command，失败回退全量。

---

## 8. 子智能体（专家）体系

### 8.1 统一定义驱动

`ExpertManager` 用 `ExpertDef` 记录驱动专家创建，消除独立 Expert 类样板。扩展新专家只需在 `buildExpertDefs()` 加一行 `ExpertDef`。

### 8.2 内置专家清单

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

### 8.3 委派范式

- **普通模式**：编排器按意图选择 `SubAgentTool` 委派，子智能体内部独立迭代，事件经 `forwardEvents` 转发回编排器流。子智能体之间互不可见。
- **能力按需路由**：`DynamicTaskTool` 根据编排器指定的能力名（web/email/system/desktop/notification/command）从 `capabilityTools` 注册表按需组合工具集。

---

## 9. 模型与传输层

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

`TokenTracker` 按会话/日期追踪用量，持久化到 H2 `token_usage_daily` 表（按工作区/日期），经 `PricingTable` 估算 RMB 成本；缓存命中率 = cachedInput/meteredInput（与 input/output 是两套口径，勿混算）。

---

## 10. 技能自学习闭环

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

## 11. 高风险工具确认机制

**总闸是 `AgentConfig.getToolReviewMode()`**（`ToolReviewMode`：MANUAL 手动审核 / SMART 智能审核（默认）/ AUTO 全自动，聊天顶栏可快切、按工作区持久化）。MANUAL 下连 NOTIFY 都升级为弹窗且不吃任务级"同意全部"；AUTO 全放行，但 JShell、高风险命令、命令白名单永久授权仍走 `requestHighRiskCommandConfirmation` 保留人工底线。

SMART 下 `ToolConfirmationManager` 通过 `UserInteractionPort` 请求确认，查询 `ToolRiskRegistry` 决定 UI 形式：

| 风险等级 | UI 形式 | 行为 |
|----------|---------|------|
| `NOTIFY` | 非阻塞 Toast | 自动放行 |
| `CONFIRM` | 弹窗确认 | 等用户决策（60s 超时拒绝；托管场景 600s） |
| `DOUBLE_CONFIRM` | 关键词二次输入 | 关键操作 |

`ConfirmDecision` 三态：DENY / ALLOW_ONCE / ALLOW_ALL（"本次允许 / 永久允许"语义）。

**调用归属靠 `ToolCallOrigin` 令牌**（装配期绑定：聊天/规划=INTERACTIVE、定时=SCHEDULED、SDD/循环=`managedTask(taskId, workDir)`）——"同意全部"白名单、目录放行与放宽超时（600s）只认 MANAGED_TASK 令牌，归属是构造期事实而非运行时推断，任务授权不会串染到聊天或定时任务。新增工具类须在构造器接收令牌并随确认调用传入。

托管场景两道免人工通道：① `ReadOnlyCommands` 确定性只读命令白名单（ls/cat/grep…直接放行，不耗 LLM）；② `LlmToolScopeAssessor` 轻量模型范围评估 + `anyPathEscapes` 确定性路径校验（影响范围限于 workDir 才放行）。

---

## 12. 异步模型与线程安全

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

## 13. 长期记忆 — MemoryService 基座

统一承载长期事实、对话情景、实体、人格、纠错记录与工作记忆检查点：EclipseStore 对象图持久化 + JVector 向量索引（一工作区一库，目录 `data/memory-stores/{workspace-id}/`）。

### 13.1 每轮的记忆链

```
用户输入
   ▼ prepareCorrectionTurn（同步！本轮模型调用前完成）
   │   CorrectionDetector 确定性正则识别强纠错措辞（"不是 X 而是 Y" / "X 错了，应该是 Y"）
   │   → CorrectionEngine 写纠错审计 → 撤销/争议化旧事实 → 写入替代事实
   ▼ recall(query)：人格（整段钉住）+ 相关纠错（最高优先级）+ 事实 Top-K + 相关情景
   │   按字符预算注入 <loaded_context>
   ▼ …编排执行…
   ▼ 交付前 CorrectionGuard：确定性拦截重复的旧结论（命中则换安全兜底文案，
   │   既不发给用户也不写回记忆）
   ▼ rememberTurn（异步 boundedElastic，失败静默）
       落情景 + Distiller 蒸馏事实（向量去重 upsert、取代检测软删除旧事实
       + 轻量实体抽取）；HabitReviewer 达水位时批量归纳"习惯偏好"
```

**为什么纠错是同步的**：异步蒸馏有延迟，下一轮很可能抢在写库前又读到已被否定的旧结论；同时"撤销记忆"这种权限刻意不交给模型自觉判断，故第一道闸是确定性正则而非又一次 LLM 调用。公共知识类纠错先落 `DISPUTED`，等核验再升格。

### 13.2 写入纪律与降级

- **单写线程**：`MemoryStore` 所有变更经单写线程串行（读/向量检索可并发）。新增写路径必须走 `MemoryStore.write(...)` 协调，勿在别的线程直接 `GigaMap.add/store`。
- **嵌入唯一入口**：`EmbeddingGateway` 承担超时、重试、**熔断**（30/60/120/240/300 秒阶梯）与健康广播；任何失败返回 `null` 降级、不抛。业务代码不得绕过它直调 `EmbeddingModel`。
- **降级不丢数据**：嵌入不可用时新事实/情景落 `pending` 暂存区（纯文本、不进索引、记忆中心可见），恢复后 `promotePendingFact` 在单写任务内完成"重嵌入 + 迁入正式索引"（幂等）。
- **状态位**：`pinned` / `userEdited` / `userAsserted`（用户手改与置顶受保护）、`superseded`（取代软删除）、`contested`（存疑）、`pending`；已 superseded / contested 的事实不进记忆图谱。
- 变更全部 append 到 `ChangeLogEntry` 审计轨（取代备份文件），记忆中心「变更日志」可读。

---

## 14. 工作区隔离与组合根

- **结构化数据**统一落全局 H2 单文件 `data/javaclaw.mv.db`，靠表内 `workspace_id` 隔离（`AppDatabase.initSchema` 是唯一 DDL 来源）；**文件资产**（记忆/知识向量库、截图、日志、浏览器 Cookie）按工作区分目录落 `data/` 下。
- `WorkspaceManager` 应用启动时最先初始化（索引自身也在 H2）。运行期对象持 `WorkspaceContext` **不可变路径快照**，禁止在执行中反复查可变单例——否则切换途中会同时看到新旧两套路径。
- **切换工作区 = `ApplicationKernel` 事务式重建**：停靠 Schedule/Plugin/SDD → 关闭旧 `WorkspaceRuntime` → 用新快照整体重建 → 全局单例（AgentConfig / DataManager / SkillUsageTracker / SkillProposalQueue / SiteCredentialManager / TraceRecorder…）`reload()` → UI 采纳新聚合根并刷新（含 ThemeManager / FontManager）。失败则切回原工作区重建。UI 不再逐个重接服务引用。
- 新增工作区级服务须挂进 `RuntimeFactory.create` 的可回滚装配，并在 `WorkspaceRuntime.close()` 释放（尤其持 EclipseStore 锁 / MCP 连接 / 线程的）。

详见 `CLAUDE.md` 的「Workspace Structure」。

---

## 15. 配置体系 — 易踩坑项

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
| `rag.embedding.dimensions` | 须与嵌入模型实际维度一致；切换模型需清空 `data/knowledge/` 与 `data/memory-stores/` |
| `memory.recall.*` / `memory.distill.*` | 召回 Top-K(8)/情景数(3)/阈值(0.3)/字符预算(8000)；蒸馏去重阈值 0.9、最小输入 10 |
| `memory.supersede.*` | 取代检测：阈值 0.55（比去重的 0.9 宽——取代关系措辞差异天然更大）、候选上限 5 |
| `memory.habit.review.*` | 习惯回顾挂在轮后链而非定时器：新情景数 + 间隔同时达标才跑一次批量归纳 |
| `memory.graph.*` | 实体抽取开关、语义边阈值 0.78、单次纳入节点上限 300 |
| 显式纠错 | **无配置开关**（确定性、不容降级）；调节只能改 `CorrectionDetector` 正则与 `CorrectionEngine` 常量 |
| `loop.*` | 轮数 25 / 用量 0=不限 / 墙钟 3600s / 间隔 300s / 单轮超时 720s / 验证超时 900s；`loop.judge.enabled` 默认 false，但目标分解不出客观准则时装配期**自动启用**验收员兜底 |
| `AgentConfig` 与 `LoopConstants` | 循环默认值在两处**各自维护**（避免 config ↔ loop 包循环依赖）；引擎内部阈值（连败 2/空转 2/相似度 0.92）不对用户暴露 |

---

## 16. 扩展指南

| 扩展点 | 步骤 |
|--------|------|
| **新子智能体** | 创建 Expert 配置 → `ExpertManager.buildExpertDefs()` 加一行 ExpertDef → SubAgentTool 自动注册 |
| **新工具** | 对应 Tools 类加 `@Tool` 方法，返回 `ToolResponse.success()/error()/timeout()`（格式 `[toolName][状态] 消息`）；高风险在 `ToolRiskRegistry` 注册等级 |
| **新技能** | `skills/{id}/SKILL.md`（frontmatter）+ 可选 scripts/references/assets；或经 skill_create / 待审提案采纳沉淀 |
| **新交互模式** | 实现 `Mode` 接口并在 `RuntimeFactory` 注册，UI 经 list() 自动渲染；终态用 `TerminalCallbackGuard` + `ConversationHandle`，勿自行处理竞态 |
| **新工作流节点** | 实现 `NodeExecutor` → 在 `PublicNodeCatalog` 注册 → 需要新工具组则同步更新 `WorkflowToolGroupPolicy` 白名单 |
| **新工作区级服务** | 构造器接收 `WorkspaceContext`（勿查 `WorkspaceManager` 单例）→ 挂进 `RuntimeFactory.create` 可回滚装配 → 在 `WorkspaceRuntime.close()` 释放 |
| **新持久化表** | DDL 加进 `AppDatabase.initSchema`，实现类构造器接收 `DatabaseAccess`（便于注入临时库单测），读写按 `workspace_id` 过滤 |
| **新 UI 框架** | 重写 `api.*` 端口的适配实现，不动领域代码 |
| **新主题** | chat.css 复制一个 `.theme-{id}` 块 + `ThemeManager.THEMES` 加一项（禁止硬编码颜色，引用 `-jc-*` 令牌） |

---

## 17. 关键源文件索引

| 文件 | 职责 |
|------|------|
| `app/JavaClawApp.java` · `app/Launcher.java` | 应用入口；Launcher 绕过 JavaFX 非模块化启动限制 |
| `runtime/ApplicationKernel.java` | 唯一组合根；重建、工作区切换/回滚与全局订阅方接管 |
| `runtime/WorkspaceRuntime.java` · `runtime/RuntimeFactory.java` | 工作区运行时聚合根 · 事务式完整装配 |
| `runtime/WorkspaceContext.java` | 工作区不可变路径快照 |
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
| `loop/LoopController.java` · `loop/ProgressGate.java` | 循环决策漏斗 · 进展判定分层 |
| `loop/CompletionChecker.java` · `loop/CriterionVerifier.java` | 完成提议解析 · 客观准则核验 |
| `loop/LoopService.java` | 循环门面：指令解析、装配、归属令牌绑定 |
| `workflow/runtime/GraphEngine.java` | 顺序有界图执行 + 逐节点检查点 |
| `workflow/service/WorkflowService.java` · `SystemGraphFactory.java` | 工作流门面 · 内置路径的只读系统图 |
| `workflow/node/WorkflowToolGroupPolicy.java` | 自定义图的工具组白名单（禁远程 MCP） |
| `memory/MemoryService.java` | 记忆门面：召回 / 纠错 / 蒸馏 / 检查点 / 人格 |
| `memory/correction/CorrectionEngine.java` · `CorrectionGuard.java` | 同步纠错写入 · 旧结论确定性拦截 |
| `memory/embed/EmbeddingGateway.java` | 嵌入唯一入口：超时/重试/熔断/健康广播 |
| `memory/store/MemoryStore.java` | 单写线程、三索引、pending 暂存区、审计轨 |
| `task/sdd/SddOrchestrator.java` | OpenSpec 六阶段确定性编排器 |
| `task/sdd/verify/ScenarioVerifier.java` | 验证单点权威 |
| `task/sdd/run/SddTaskManager.java` | SDD 任务生命周期与持久化 |
| `skill/SkillManager.java` · `skill/curation/SkillCurator.java` | 技能加载/版本管理 · 自学习蒸馏 |
| `chat/ChatViewController.java` | 普通模式 UI、回调注册与气泡渲染 |
| `ui/javafx/JfxUserInteractionPort.java` | UserInteractionPort 的 JavaFX 实现 |
| `config/AgentConfig.java` | 配置管理与系统提示词定义 |
