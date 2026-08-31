# ADR-0008：能力型 Network Broker

- 状态：Accepted
- 日期：2026-08-27

## 决策

`ALLOWLIST` 不向普通 sandbox 进程开放原始 socket。第一方 Web 工具、HTTP MCP 和允许联网的
服务通过 App Server 的 Network Broker 发起请求。Broker 在每次 DNS、连接和重定向重新检查
scheme、host、port 与解析后的全部 IP，拒绝 loopback、私网、链路本地和不匹配目标，并限制
超时、重定向次数和响应体。

Shell 需要任意网络只能申请交互式 `HOST_FULL_ACCESS`；无人值守 Schedule 永远不能获得。
平台不能通过 Broker 表达的协议直接失败关闭。

## 结果

hostname allowlist 不再依赖容易被 DNS rebinding、代理或重定向绕过的子进程网络 namespace。
