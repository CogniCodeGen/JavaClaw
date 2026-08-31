# ADR-0012：干净调用链、Turn 工具快照与 MCP 客户端边界

- 状态：Accepted
- 日期：2026-08-28

## 背景

9 模块方案降低了构建复杂度，但模块合并后仍可能出现三类逻辑耦合：协议 Handler 直接调用
具体 Service、H2 胖实现被多个领域复用、以及全局工具表把 Plugin/MCP 生命周期冻结在进程启动时。
这些耦合会让 UI、协议、运行时和基础设施再次形成隐式调用链。

## 决策

JavaClaw 内部调用链固定为：

```text
Desktop / CLI
→ Java SDK 领域客户端
→ SDK 内部 Protocol Mapper
→ JSON-RPC Transport
→ AppServerSession
→ RpcRouter
→ 领域 RpcHandler
→ 窄 UseCase 接口
→ Agent Runtime / Server Service
→ Repository / Model / Extension Adapter
```

`AppServerMain` 只创建并关闭 `server.bootstrap.ServerComponentGraph`。连接、路由、Handler、请求
校验和 `ProtocolMapper` 位于 `server.transport`；该包禁止依赖 H2 与具体业务 Service。Provider、
Plugin、MCP、Diagnostics、Configuration、Discovery、Network、Browser、Collaboration 和 Sandbox
实现分别回到功能包。Server Service 只返回领域值；`Wire*` 和 JSON 树只在协议层生成。

Agent Runtime 使用 `WorkspaceUseCases`、`ThreadUseCases`、`TurnUseCases`、
`InteractionUseCases`、`ProfileUseCases`、`KnowledgeUseCases` 和 `AutomationUseCases` 等窄端口。
`javaclaw-api` 只保留跨模块不可变领域值和沙箱契约，不开放嵌入式 Runtime。

H2 对外实现拆为 Workspace、Thread Journal、Outbox、Interaction、Attachment 和 Configuration
Repository。Workspace、Outbox、Interaction、Configuration 已直接基于共享 `H2Database`；Thread
Journal 保留事务协调器以保证 Thread/Turn/Item、Event 与 Outbox 原子提交，Attachment 聚合仍由
同一内部引擎协调。架构测试只允许这两个聚合 Adapter 和 `H2Persistence` 引用内部引擎。

工具发现由多个 `ToolProvider` 组合。每个 Turn 创建不可变 `TurnToolSession`，冻结描述符、Schema、
来源、风险和权限上限；外部 Provider 故障只生成 Error Item。执行前重新检查外部来源的 enabled、
revision 和权限，因此禁用可以立即撤销旧快照的执行权。

MCP 客户端只存在于 `server.extension.mcp`，固定协议 `2026-07-28`，不降级到旧握手。审阅的官方
Schema 固定为提交 `271ecc9accafdd9b83a3c869fa67c22953b2af80`，文件 SHA-256 为
`ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203`。stdio MCP 只能经
Sandbox Supervisor 启动且无原始网络；HTTP、OAuth 和重定向只能经 Network Broker。
Resource、Prompt、Completion 显式工具化，不自动注入上下文。MRTR、Tasks、Progress、Cancel、
Pagination、TTL 与 Subscription 都受 Turn 时间、模型调用次数、往返次数和有界队列约束。

SDK 的公开入口为八个领域客户端；公开签名只允许 JDK 与 `com.javaclaw.sdk.model` 类型。原始
`request(method, JsonNode)` 只属于包内连接实现，不能从 `JavaClawClient` 暴露；Turn 统一经
`ThreadClient.startTurn(TurnStartRequest)` 创建，客户端无权传入工作目录或 Sandbox Policy。

## 结果

业务能力可在 Desktop、CLI 与第三方本机应用之间复用，但复用边界始终是 SDK/App Server。
协议、业务、持久化和进程隔离各有单一映射点；架构测试固定八组 Handler、窄用例依赖、H2 引擎
引用白名单、Discovery 无 Jackson、客户端无 Protocol/Jackson，以及唯一 Component Graph。

官方基线：

- [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- [MCP 2026-07-28 Schema](https://modelcontextprotocol.io/specification/2026-07-28/schema)
- [固定的官方 Schema 源文件](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/271ecc9accafdd9b83a3c869fa67c22953b2af80/schema/2026-07-28/schema.json)
- [MCP Tasks 扩展](https://tasks.extensions.modelcontextprotocol.io/specification/draft/tasks)
