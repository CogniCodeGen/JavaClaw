# ADR 0003：Protocol v2 与能力协商

- 状态：Accepted
- 日期：2026-09-01

## 背景

本地客户端、后台 Server 和扩展 UI 需要独立升级。隐式版本推断和宽松 JSON 解析会使同一输入产生不同语义。

## 决策

采用严格 JSON-RPC 2.0 与 `appProtocolVersion=2`。连接先 initialize，stable capability 在 5.x 只兼容扩展，
experimental 内容必须显式协商。Core 方法保持小目录，领域方法统一走 Extension RPC。写命令统一使用幂等键与
expected revision。Core ID 使用标量字符串，时间与 Duration 使用 ISO-8601 文本。扩展写入只发送无业务正文的
`extension/event` 失效通知，客户端随后重新读取权威状态。

## 结果

不支持的版本、对象包裹 ID 和 timestamp 旧表示直接失败，服务端不猜测客户端意图。stdio、UDS 与 Named Pipe
共享 framing 和 wire schema；不提供 TCP/WebSocket transport。
