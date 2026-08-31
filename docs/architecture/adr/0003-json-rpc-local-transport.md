# ADR-0003：JSON-RPC v1 与本机传输

- 状态：Accepted，三平台实现完成；Windows 原生验证由 CI 执行
- 日期：2026-08-27

## 决策

- 线协议使用标准 JSON-RPC 2.0并保留 `"jsonrpc":"2.0"`。
- JavaClaw 协议版本从 1 开始，经 `initialize` 协商；不承诺 Codex 线级兼容。
- 临时 CLI/SDK 使用 stdio JSONL。
- macOS/Linux 常驻服务使用当前用户独占的 Unix Domain Socket；owner/mode 校验与文件锁共同保证
  单实例，竞争者不得替换或删除活动端点。
- Windows 使用独立 Native Transport Host 创建 Named Pipe：DACL 只允许当前用户 SID，拒绝
  remote client，并在 impersonate 后复核客户端 SID。Host 与 App Server 通过带 connectionId
  的有界 mux 帧通信；SDK 使用同一 Host 的 client bridge，不直接调用 FFM。
- 4.0 不提供 TCP 或 WebSocket。
- durable replay 来自 H2；live 队列只负责低延迟通知，过载时发送 `resyncRequired`。

## 兼容策略

Schema 和方法 golden file 随协议模块发布。Item kind 与事件 payload 保持开放；破坏性字段变更必须
提升协议版本。未知方法按 JSON-RPC `method not found` 返回，握手前的请求返回专用未初始化错误。

## 失败策略

Windows Host、Named Pipe SID 检查或 mux supervision 任一不可用时，SDK/Desktop 不回退 stdio。
对应平台必须由原生 CI 通过后才可创建 Release。
