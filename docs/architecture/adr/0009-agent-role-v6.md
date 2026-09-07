# ADR 0009：Agent Role、独立执行配置与 v6 破坏性基线

- 状态：Accepted
- 日期：2026-09-07
- 取代：v5 完整设计中的胖 AgentProfile、ProfileBinding、预设实例化决策；ADR 0003 的 Protocol v2 与
  ADR 0005 的 data-v5 基线选择。ADR 0001 的 14 模块、单一 Server 和 SDK-only 边界继续适用。

## 背景

v5 AgentProfile 同时绑定职责、Provider、权限、审批与预算，切换模型或权限会迫使用户创建新 Profile。
“角色”与“此次执行的配置”因此无法独立复用；自动化、子任务与 Prompt 来源也难以表达同一解析结果。
仅重命名 Profile 或在其旁边新增 Role 会保留两条权威路径，无法解决重复状态。

## 决策

使用 `AgentRole`/`AgentRoleSpec`/`AgentRoleRef` 表达可复用职责。Role 包含 developer instructions、可选模型/推理
约束、能力与 Skill 收窄和权限约束，不包含 PermissionProfile、CredentialRef、审批策略或预算。删除旧 AgentProfile、
ProfileBinding、Profile preset API、服务、表和 SDK 入口，不保留别名或兼容 facade；PermissionProfile 保持独立。

安装默认、Workspace、Thread 与单次调用分别保存独立覆盖。唯一服务端解析器逐字段继承，最后应用 Role 显式约束，
校验精确引用、生命周期、模型用途、权限和预算，输出 `ResolvedTurnConfig` 与字段来源。Turn、子任务、MCP sampling、
Prompt 优化和 Automation 使用同一解析入口；Turn 与 Automation 在创建事务中冻结结果，恢复和幂等重放不重新解析 latest。
父任务、系统、Workspace 和实时撤权构成上限，角色只收窄。不可用模型或不支持的推理值必须给出明确失败。

Protocol v3 的 157 个 RPC 包含 `execution/subagent/read` 与 `execution/subagent/update`，用于安装及 Workspace
子智能体默认 Provider/reasoning；其他执行字段不允许写入该默认配置。子模型顺序为 Role 固定值、显式 spawn、
子智能体默认值、父模型，子任务安全与预算上限继续继承父 Turn。

内置 `default`、`worker`、`explorer`、`software-engineer` 带 revision 和文本摘要，只读且支持 clone。
Workspace 创建默认选择 default。explorer 的只读由代码交集保障，提示词不能代替权限检查。

Prompt 的 system、developer、response contract 分层进入模型边界。公开参考固定为 OpenAI Codex commit
`8b8ee28a9b0df8188fec8d4e3855a7b3af3ed8a2` 的 `codex-rs/core/gpt_5_codex_prompt.md`，原文 SHA-256 为
`42842be69650ae563d212695e8d3f3591534908fd8ca33b63f742daf41f88b65`。每个本地模板的 hash、差异与用途见
[`role-provenance-v1.json`](../../../javaclaw-app-server/src/main/resources/prompts/role-provenance-v1.json)。
不能把此 commit 中空的 explorer 模板描述为可复用正文，也不声称角色文本经过真实效果对照评估。

Role 文件交换采用显式预览、确认和导出，提供 Codex portable 和 JavaClaw lossless 模式。TOML 文件单份最多
1 MiB、UTF-8；无法唯一映射的模型由用户明确选择，文件更改不自动影响 H2 中运行角色。

版本统一为 `6.0.0-SNAPSHOT`、Protocol v3 与全新 `data-v6` baseline。拒绝 Protocol v2 和旧 Profile payload，
不探测、读取、迁移、修改或清理 data-v5。现有 14 模块、JavaFX/FXML/CSS、SDK 与安全/扩展执行链继续复用。

## 结果与验收

模型、角色和权限可以独立选择，配置来源和模型锁定原因可见。持久化、RPC、SDK、Runtime、自动化和 Desktop 一次切换，
不以双栈过渡隐藏不一致。代价是 v5 数据与客户端不兼容，用户需显式配置新基线。

发布须重新完成严格 Schema、配置继承/冻结/重启、权限不扩张、文件往返、指令层/Provider state、SDK/UI、三平台、
覆盖率和性能验收。旧版全仓通过记录与现有 54 张视觉参考只作各自范围的依据，不代表 v6 已通过。
具体未完成项见[验收矩阵](../acceptance-matrix.md)。
