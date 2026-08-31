# ADR-0001：本机 App Server 进程模型

- 状态：Accepted
- 日期：2026-08-27

## 背景

3.x 的 JavaFX、Spring 工作区 Context、AgentEngine、Store 和 ViewFactory 位于同一进程及生命周期。
这让无头入口、多客户端复用、崩溃恢复和权限隔离都依赖桌面实现细节。

## 决策

4.0 采用一个本机单用户 App Server 作为运行时控制面：

- 只有 App Server 持有 Thread Manager、Agent Kernel、H2 写连接、模型 Adapter 和工具治理。
- Desktop、CLI 与第三方本机应用只能通过 Java SDK/JSON-RPC 调用。
- Spring 只用于 App Server 进程级装配和 Spring AI Adapter；不创建工作区子 Context。
- 第三方代码与 Agent 控制的命令均在 App Server 之外执行。
- 4.0 不设计远程、多租户、TCP/WebSocket 或水平扩展。

## 结果

运行时可被多个客户端复用，客户端崩溃不再等于 Turn 崩溃，H2 单写者也有明确所有权。代价是
所有 UI 状态必须映射为协议 DTO，且桌面迁移完成前会暂时存在一个明确标记的遗留模块。
