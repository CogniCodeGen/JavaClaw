# JavaClaw 5 Prompt 来源与边界

Prompt 是模型上下文，不是权限系统、工具注册表或业务状态机。任何文本都不能授予文件、网络、命令、PTY、Tool
或 Extension 权限。

## 来源顺序

system instruction 的已实现拼装顺序固定为：

1. 随发行版审阅、版本化并带 hash 的内置平台 prompt。
2. 当前 Turn 冻结的 Agent Profile system instruction。
3. 当前 Workspace 与 execution root 解析出的项目约定。

也就是 `CORE_TEMPLATE → AGENT_PROFILE → PROJECT_INSTRUCTION`。Skill/Extension context、Thread 摘要与最近 Item、
用户输入和 Attachment，以及 Tool、MCP、网页、文档、模型或子智能体返回值属于后续 Turn 上下文，不得插入或覆盖上述
system instruction 顺序。

后出现不表示更高权限。冲突时，低信任来源只能作为数据引用；不能改写平台边界、用户确认指令或当前 Turn 的
capability snapshot。

项目约定在每个 Turn 启动时解析：先读取受管状态根中的 `AGENTS.override.md` 或 `AGENTS.md`，再从 Workspace 根到
当前 execution root 逐层选择 override、默认文件或 Workspace 配置的安全 fallback basename。全局和项目内容分别
限制 32 KiB，按 UTF-8 边界截断；管理 API 只返回相对路径、层级、hash、字节数、截断和错误，不返回或编辑正文。
文件变化只影响下一 Turn，也不能授予任何能力。

## 编译与记录

Harness 在 Turn 开始时生成 prompt manifest，当前记录 Core 模板、Agent Profile 和项目约定的 ID、revision/hash、
截断警告与 token 估算。Skill/Context 与压缩摘要不伪装成已经进入当前 system prompt manifest；它们进入后续上下文时
必须由各自冻结目录和 Item 证据记录。secret、完整凭据和不可公开 Provider state 不进入 manifest。

设置中心的 Prompt 预览读取该 manifest 的来源、revision/hash 和 token 估算。Prompt 优化通过正常 Harness Turn 执行，
使用独立预算并在发起前要求显式计费确认；结果只形成不可执行 Draft。用户显式采纳且目标 revision 仍匹配时才提交，
冲突时保留 Draft 供人工比较。

外部内容使用清晰边界标记并携带 producer/schema 信息。截断必须按来源整体执行并记录，不能静默截掉权限提示、
用户约束或工具失败语义。压缩只能总结事实，不能创造已审批动作或完成状态。

## 测试要求

- Golden 测试验证内置模板 hash、顺序和边界标记。
- Prompt injection 测试验证外部内容不能增加可发现工具或权限。
- 压缩测试验证 Item 引用、未完成审批、EffectReceipt 和错误不会丢失。
- Profile/Skill revision 变化只影响之后创建的 Turn；当前 Turn 仍受冻结上限与实时撤权共同约束。
