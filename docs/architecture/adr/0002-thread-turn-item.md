# ADR-0002：Thread / Turn / Item 统一语义

- 状态：Accepted
- 日期：2026-08-27

## 背景

3.x 同时公开 Session、Conversation、Run、Message 与 Event，聊天历史又由客户端在每轮重新提交。
存储、恢复、UI 流式投影和多智能体因而使用不同身份与生命周期。

## 决策

- Thread 是持久上下文、历史、fork 与归档边界。
- Turn 是一次用户请求及其完整执行；同一 Thread 至多一个活动 Turn。
- Item 是所有可观察输入输出的开放联合类型。
- Run 降为内部 ExecutionAttempt，只承担重试、用量和诊断。
- ThreadEvent 使用严格递增 sequence；客户端以 `afterSequence` 恢复。
- compaction 追加摘要 Item，不删除历史；不持久化原始思维链。
- 子智能体表示为父子 Thread，不再拥有第二套 Runtime。

## 结果

持久 transcript、协议事件、SDK 恢复与 UI 投影共享同一语义。新增 Item kind 无需提升协议主版本，
但客户端必须保留未知 kind，并避免穷举失败。
