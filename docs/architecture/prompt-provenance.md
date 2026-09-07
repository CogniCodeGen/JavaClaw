# JavaClaw 6 Prompt 来源与边界

Prompt 是模型上下文，不是权限系统、工具注册表或业务状态机。任何文本都不能授予文件、网络、命令、PTY、Tool
或 Extension 权限。Role 只规定职责和收窄约束，不替代服务端执行配置与权限交集。

## 来源与模型边界

可审计来源顺序为 `MODEL_BASE → PLATFORM → AGENT_ROLE → PROJECT_INSTRUCTION → RUNTIME_CAPABILITIES`。
已冻结的 Skill 与带来源的 Context 使用各自类别，不得伪装成平台指令。Tool、MCP、网页、文档、Attachment、摘要和
子任务结果属于外部数据；它们不能覆盖用户指令、平台约束或本 Turn 实际 capability snapshot。

`ModelInstructions` 分开保存 system、developer instructions 和 response contract。模型基础与平台边界属于
system；Role 职责、项目约定与当次能力摘要按稳定顺序进入 developer 层；输出约束使用独立 contract。OpenAI Responses
使用独立 developer message，不把用户内容提升到 developer。Spring AI 无通用 developer role 的入口只在 Adapter
边界按 system → developer → contract 合并，顺序固定且内容不丢失。

Provider state 绑定完整指令层；Role、项目约定或 response contract 变化时不得用旧状态继续先前指令。Compaction
保留相同层级和 opaque items，不能凭摘要宣称权限、审批或任务已完成。

## 内置角色来源

内置 `default`、`worker`、`explorer`、`software-engineer` 的职责按 JavaClaw v6 设计实现，文本随发行版审阅、版本化。
公开参考锁定到 Codex commit `8b8ee28a9b0df8188fec8d4e3855a7b3af3ed8a2` 的
[`gpt_5_codex_prompt.md`](https://github.com/openai/codex/blob/8b8ee28a9b0df8188fec8d4e3855a7b3af3ed8a2/codex-rs/core/gpt_5_codex_prompt.md)。
原文 SHA-256、各本地模板 SHA-256、revision 和改编差异记录在
[`role-provenance-v1.json`](../../javaclaw-app-server/src/main/resources/prompts/role-provenance-v1.json)。
源 commit 中空的 explorer 配置不能作为正文来源；explorer 只读能力来自 JavaClaw 的代码约束。

改编去除 Codex 产品身份、内部工具、专属终端和无条件并行假设，保留证据、范围、验证与保护用户成果的原则。
software-engineer 遵守已有产品视觉体系。模板没有经过付费模型对照评估，不宣称任务质量或效率提升。

## 项目与快照

项目约定每个 Turn 启动时解析：先读取受管状态根中的 `AGENTS.override.md` 或 `AGENTS.md`，再从 Workspace 根到
execution root 逐层选择 override、默认文件或安全 fallback basename。全局和项目分别限制 32 KiB，按 UTF-8
边界截断；管理 API 只返回相对路径、层级、hash、字节数、截断和错误，不返回或编辑正文。内容变化只影响之后的 Turn。

服务端将精确 Role、Provider、Permission、字段来源、Prompt manifest 与工具目录摘要冻结到 `ResolvedTurnConfig`。
Prompt 预览来自同一解析器，展示来源、revision/hash、token 估算与能力事实；Role 声明超出可用目录时不能扩大实际能力。
Secret 和不可公开 Provider state 不进入 manifest。Skill/Context 必须有对应冻结目录和 Item 证据，不能仅凭类别存在
就宣称内容已经进入模型上下文。

Prompt 优化通过正常受预算 Harness Turn 执行，发起前要求明确计费确认，只产生 Draft。用户显式采纳且可编辑 Role
revision 匹配后才提交；冲突保留 Draft。内置角色需先 clone，导入文件也必须预览差异并确认，不能后台改写角色。

## 验证边界

模板摘要、分层顺序、角色约束、外部内容不增权、指令变化拒绝旧 state 和 compaction 语义应由无付费调用的契约测试
验证。三平台 UI、真实模型支持和效果对照不由这些测试替代，当前验收状态见[验收矩阵](acceptance-matrix.md)。
