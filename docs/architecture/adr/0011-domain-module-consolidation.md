# ADR-0011：按功能领域收敛 Maven 模块

- 状态：Accepted
- 日期：2026-08-28

## 背景

4.0 初始物理拆分把 API、Core、Runtime、Store、Provider、Feature、FFM 与多个入口分别做成
23 个 Maven 模块。安全与进程边界清晰，但大量只有数个类的技术模块增加了 Reactor、测试夹具、
发行脚本和日常重构成本；这些边界中只有协议、客户端、原生辅助进程和独立服务是真正需要的
物理隔离。

## 决策

Reactor 精确收敛为 9 个功能领域模块：

```text
javaclaw-api             → 无 JavaClaw 依赖
javaclaw-protocol        → 无 JavaClaw 依赖
javaclaw-agent-runtime   → api
javaclaw-app-server      → api + protocol + agent-runtime
javaclaw-native-hosts    → api + protocol
javaclaw-browser-service → protocol
javaclaw-client          → protocol
javaclaw-desktop         → client
javaclaw-packaging       → app-server + native-hosts + browser-service + client + desktop
```

Agent、Server 与 Native 内部技术边界改由领域包和源码架构测试约束。公共
`com.javaclaw.core.api`、`com.javaclaw.sandbox.api`、`com.javaclaw.protocol` 与
`com.javaclaw.sdk` 包保持不变；旧 artifactId 不保留兼容空壳。测试夹具回到所属模块测试源码，
H2 Runtime 测试成为 App Server 集成测试。

`javaclaw-native-hosts` 合并 JAR 但不合并进程：Sandbox Launcher 与 Windows Transport Host
继续以不同 JVM、管道和权限上下文运行，FFM 包不导出。设计稿中的
`com.javaclaw.native.hosts` 不能作为合法 JPMS 名称，因为 `native` 是 Java 保留字；实际命名为
`com.javaclaw.nativehosts`，并使用
`--enable-native-access=com.javaclaw.nativehosts`。

## 结果

构建与发行单元从 23 个降为 9 个，JSON-RPC v1、Thread/Turn/Item、H2 v4、Plugin 4.0 和沙箱
行为不变。安全边界继续由进程、未导出 FFM 包、唯一 App Server 写连接和失败关闭测试保证；
代码归属由包级门禁防止重新混杂。
