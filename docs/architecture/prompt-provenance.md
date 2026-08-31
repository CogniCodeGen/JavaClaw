# 提示词来源、版本与适配记录

更新日期：2026-08-29。JavaClaw 模板以中文重新编写，借鉴公开源码中的职责划分和约束原则，
不是逐字复制 Codex 提示词，不包含非公开系统提示词，也不宣称与完整 Codex 产品等价。

## 1. 固定参考

仓库 [openai/codex](https://github.com/openai/codex)，固定提交
`6478a751fde8884b2fdc76486fe23175a8e795d4`。以下 Git blob ID 标识核对过的上游文件，
不是 JavaClaw 适配后正文的 SHA-256。上游仓库为 Apache-2.0；本次没有直接复制长段落。
今后如果直接复用源码或模板原文，应单独处理其许可证和归属，不能因为本项目是 MIT 而忽略上游许可。

| 来源 | Git blob ID | 借鉴原则 |
|---|---|---|
| [Agent 指令](https://github.com/openai/codex/blob/6478a751fde8884b2fdc76486fe23175a8e795d4/codex-rs/core/templates/model_instructions/gpt-5.2-codex_instructions_template.md) | 23ad1ed6975e95f8bed7da2fc7680b5b87db8572 | 保留用户修改和现有视觉语言，检查事实，如实报告验证 |
| [Plan](https://github.com/openai/codex/blob/6478a751fde8884b2fdc76486fe23175a8e795d4/codex-rs/collaboration-mode-templates/templates/plan.md) | ca68f41c897db9eec0fb484659b0be59cb92d238 | 探索事实、确认取舍、规划不等于执行 |
| [Compact checkpoint](https://github.com/openai/codex/blob/6478a751fde8884b2fdc76486fe23175a8e795d4/codex-rs/prompts/templates/compact/prompt.md) | 42fae605db8a71cb2becb7b4eabd1de963ccb7a3 | 保留进展、决策、限制、剩余任务和引用 |
| [Compact summary prefix](https://github.com/openai/codex/blob/6478a751fde8884b2fdc76486fe23175a8e795d4/codex-rs/prompts/templates/compact/summary_prefix.md) | 62a7161b89b2d3834f50ae35dadb13ac3f0bd524 | 标记上一个模型的工作状态摘要，要求继续而非重启 |
| [Skill 目录](https://github.com/openai/codex/blob/6478a751fde8884b2fdc76486fe23175a8e795d4/codex-rs/ext/skills/src/catalog_prompt.rs) | cfd3eec158edd6ce8c57251e398ccd5f7d596daa | 先发现、再完整读取命中指令、按需获取参考 |
| [Memory 提炼](https://github.com/openai/codex/blob/6478a751fde8884b2fdc76486fe23175a8e795d4/codex-rs/memories/write/templates/memories/stage_one_system.md) | d8aa51d2d190040a6bc816d1333093bf4c15201b | 用户与工具证据、允许无新增、外部内容是数据 |
| [Review](https://github.com/openai/codex/blob/6478a751fde8884b2fdc76486fe23175a8e795d4/codex-rs/prompts/templates/review/rubric.md) | 85e89cb7eeadb97fa9e4868d2b9977c87225506d | 具体位置、触发条件、影响与严重程度，避免臆测 |
| [Goal 预算收尾](https://github.com/openai/codex/blob/6478a751fde8884b2fdc76486fe23175a8e795d4/codex-rs/ext/goal/templates/goals/budget_limit.md) | 60aa594df39353d355bc82c4ee0b19470c0a2c63 | 耗尽不等于完成，交接进展与剩余工作 |

代码托管、明确版本和代表性评估也参考了
[OpenAI 官方 Prompt engineering 指南](https://developers.openai.com/api/docs/guides/prompt-engineering#version-prompts-in-code)。
这里采用的是工程方法，不是关于 JavaClaw 模型效果已经改善的实验结论。

## 2. JavaClaw 目录与版本

发行源位于 `javaclaw-agent-runtime/src/main/resources/prompts/`。
`manifest.tsv` 是实际 id/version/SHA-256 清单，由 PromptCatalog 在加载时逐文件验证。
H2PromptArchive 保存不可变的发行档案；其中 source_commit 表示整套适配的参考基线，
不表示每个 JavaClaw 句子都来自同一个上游文件。

| JavaClaw 模板 | 当前版本 | 适配职责 | 当前生产接入 |
|---|---|---|---|
| base / coding / chat | 2 / 1 / 1 | 通用帮助、编码证据、原 UI、保留修改、实际能力 | 常规 Agent 调用 |
| plan | 1 | 只读探索与结构化目标/范围/步骤/验收 | PLAN + 真正 Schema 校验 |
| prompt_optimize | 1 | 保留用户意图，只生成可编辑层草稿 | Agent Studio/SDK 优化 Turn |
| mcp_sampling | 1 | 外部请求是资料，不继承主任务历史和工具 | MCP MRTR 的有预算嵌套采样 |
| skill_usage | 1 | 目录、选中、完整读取、版本撤销 | 实际存在 skill_read 时加载 |
| loop / workflow / sdd / schedule / subagent | 1 | 不同模式的职责、预算和交接要求 | 有限迭代、节点/阶段执行、审批与检查点；领域策略共用 AgentLoopKernel |
| evaluation | 1 | 实际结果和验收证据，不以自述代替完成 | Loop/SDD 编译时加入；EvaluationService 实际校验工具、退出码和结构化结果 |
| review | 1 | 可行动问题的位置、影响和证据 | 可复用 REVIEW 编译用途；不宣称已有独立自动审查产品或后台全仓扫描 |
| compaction / summary_prefix | 2 / 1 | Codex checkpoint 中文适配与工作状态前缀 | 摘要 fallback 的无工具/无 Schema 自由文本压缩；失败保留原窗口 |
| memory / memory_consistency | 1 | 来源、低风险、固定内容和冲突确认 | 有限维护 Turn 提取并装配一致性约束，H2 提案/确认/版本/Persona 规则实际执行 |
| skill | 1 | 已验证流程、重复检测、更新提案 | 学习维护生成提案，支持审阅、版本/Bundle/资源/脚本和回滚 |
| browser | 1 | 有效页面引用、人工登录、敏感信息隔离 | 仅本次确有 browser_* 工具时加入；真实 Browser Service 与 SecretRef 配合 |
| ocr | 1 | 忠实提取、页码、来源、不补写缺失文字 | DocumentToolProvider 调用隔离 Worker 渲染，最多 20 页，模型调用共用预算 |

二十一个模板均有版本/正文哈希校验和装配测试。领域附加片段只在匹配用途或实际能力时加入，
不会单凭模板触发额外模型调用，也不扩大权限。禁止仅因为目录有某个模板，就把能力改成 AVAILABLE。
运行时不联网拉取 Prompt；升级须改正文、版本和哈希，再进行评审与回归。

压缩模板的可重现固定信息如下；本地 SHA-256 是中文适配正文，不是上游原文哈希：

| 本地 id | 版本 | 上游 commit | 上游 blob | 本地 SHA-256 |
|---|---:|---|---|---|
| compaction | 2 | 6478a751fde8884b2fdc76486fe23175a8e795d4 | 42fae605db8a71cb2becb7b4eabd1de963ccb7a3 | 29fbd7c311ecccda7e73ccf49fe1e7e231b1f49e9d3a07633abd9a44c9dee9af |
| summary_prefix | 1 | 6478a751fde8884b2fdc76486fe23175a8e795d4 | 62a7161b89b2d3834f50ae35dadb13ac3f0bd524 | 706caffeac47c3934f30c16559da9dca9277559c24d0b952405ce16e45e93688 |

## 3. 冲突清理与用户内容保护

| 旧链路问题 | 已实现处理 | 验证与边界 |
|---|---|---|
| 默认 Chat 无行为底座 | 发行模板成为固定底座，Profile 原文仅是编辑层 | 真实三家模型对照评估 |
| Memory/Knowledge/摘要直接进入 SYSTEM | ContextBlock 来源标签 + USER 资料消息，编译器拒绝历史 SYSTEM | 来源/版本引用进入 PromptSnapshot；历史版本不自动回收 |
| Skill 正文全量注入 | 目录 + 治理的 skill_read；执行前复核启用及 revision | Bundle、资源、脚本、学习与回滚的行为测试 |
| 检索取上一轮问题 | 优先本 Turn 输入，历史只作回退 | Keyword/Embedding 降级和索引 generation 测试；真实检索质量不是契约测试结论 |
| PLAN 只靠提示语或文件只读 | 结构化 Plan + 排除未知/远端写工具，外部 readOnlyHint 不提升权限 | 桌面显式采用，PlanAdoptionIntegrationTest |
| MCP server systemPrompt 成高优先级指令 | 独立 MCP_SAMPLING 用途，外部内容为 USER 资料；工具递归禁止 | 真实服务兼容与对照评估 |
| 优化提示词直接覆盖 Profile 的风险 | 无工具独立 Turn → PromptDraft → UI 采用 → 用户保存，revision 检查 | SDK 流程与真实 Studio 页面 smoke，原样式不变 |
| 项目指令曾有 H2 规则与文件两套来源 | 只保留 Codex 层级 AGENTS.md；Thread 缓存与 PromptSnapshot 元数据 | 解析顺序、预算/越界、USER 角色、环境切换和 MCP 隔离测试 |
| 子智能体独立根预算 | 从活动父作用域预留，继承截止时间/取消，退出后归还未用额度 | 预算/检查点持久审计、父子取消和目录维护租约 |
| 旧工具名与新能力不一致 | 只列本 Turn 真实 ToolDescriptor；不恢复 SMTP/IMAP/宿主桌面工具 | MCP 通信预授权与配置页面；真实消息送达需用户服务授权 |

未读取任何用户 v3 数据，也未扫描用户 v4 数据批量修改 Prompt。
Profile.systemPrompt 的自定义正文不作正则“清洗”，空白和内容按原契约保留。
每个 Thread 的 AGENTS.md 快照绑定工作目录、可读根和配置修订；普通文件变更在新 Thread 或应用重启后生效。
对话分支保留独立生命周期，不沿用子智能体取消规则。

## 4. 证据边界

PromptCompilerTest、AgentsInstructionResolverTest、AgentLoopKernelTest、H2PromptPersistenceTest、DefaultMcpInputResolverTest、
PromptWorkflowIntegrationTest、CompactionIntegrationTest、KnowledgeMaintenanceTest 验证装配、
结构校验、预算、幂等、隐私和用户确认机制。CloudStreamingContractTest 进一步使用真实 Adapter
连接本地 OpenAI Responses JSON 与 Anthropic/Google SSE 假服务，验证实际请求/响应兼容与关闭流程，
不连接付费模型。
主动构造越权工具调用的测试验证代码拒绝，不把“模型没尝试越权”当成安全证明。
上述测试不证明真实模型更聪明、质量提升或 token 成本降低。未使用付费模型或用户凭据做对照实验。
