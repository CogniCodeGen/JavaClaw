# JavaClaw 5 Prompt 来源与边界

Prompt 是模型上下文，不是权限系统、工具注册表或业务状态机。任何文本都不能授予文件、网络、命令、PTY、Tool
或 Extension 权限。

## 来源顺序

从稳定到动态依次为：

1. 随发行版审阅、版本化并带 hash 的内置平台 prompt。
2. 当前 Workspace 已确认的项目指令与 Profile。
3. 当前 Turn 明确启用、经冻结目录定位的 Skill/Extension context。
4. Thread 摘要和最近 Item。
5. 用户本轮输入与 Attachment 提取文本。
6. Tool、MCP、网页、文档、模型和子智能体返回的外部内容。

后出现不表示更高权限。冲突时，低信任来源只能作为数据引用；不能改写平台边界、用户确认指令或当前 Turn 的
capability snapshot。

项目约定在每个 Turn 启动时解析：先读取受管状态根中的 `AGENTS.override.md` 或 `AGENTS.md`，再从 Workspace 根到
当前 execution root 逐层选择 override、默认文件或 Workspace 配置的安全 fallback basename。全局和项目内容分别
限制 32 KiB，按 UTF-8 边界截断；管理 API 只返回相对路径、层级、hash、字节数、截断和错误，不返回或编辑正文。
文件变化只影响下一 Turn，也不能授予任何能力。

## 编译与记录

Harness 在 Turn 开始时生成 prompt manifest，记录模板 ID、版本/hash、Profile revision、启用 Skill/Context revision、
压缩摘要 hash 与 token 预算。secret、完整凭据和不可公开 Provider state 不进入 manifest。

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
