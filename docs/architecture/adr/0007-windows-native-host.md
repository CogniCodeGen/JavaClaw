# ADR-0007：Windows 独立 Native Transport Host

- 状态：Accepted
- 日期：2026-08-27

## 背景

App Server/SDK 直接调用 Named Pipe FFM 会扩大可信计算基并污染客户端模块边界。

## 决策

Windows Transport Host 是独立命名辅助进程，也是 Named Pipe FFM 的唯一调用者。Server 模式
监督 App Server 子进程，Named Pipe DACL 只允许当前 SID、拒绝远端并复核 impersonated client
SID；每连接映射为有界 `LocalMuxFrame(connectionId)`。Client bridge 把 SDK stdio 映射到同一
Pipe。Host 崩溃由 SDK Supervisor 按退避策略重启；Windows 不允许 stdio fallback。

## 结果

App Server、SDK、CLI 与 Desktop 都不依赖 FFM。Host 是发行物内部启动器，不监听 TCP。
