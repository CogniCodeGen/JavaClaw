# ADR-0005：进程外 Plugin 4.0

- 状态：Accepted
- 日期：2026-08-27

## 背景

可信签名可以证明 JAR 来源，却不能把任意第三方字节码变成 JVM 安全边界。主 JVM ClassLoader
扩展可读取密钥、修改全局状态或终止整个 App Server。

## 决策

- Plugin API 4.0 是声明包，不提供 Java Class、ClassLoader 或主 JVM SPI 入口。
- Manifest 可贡献 Skill，以及 MCP、Hook、Service 等外部进程。
- 签名只参与来源策略，不自动提升文件、网络或进程权限。
- 所有进程贡献必须经 SandboxExecutor，并取 Manifest 请求与服务端 authority ceiling 的交集。
- PreTool/安全 Hook 同步、限时、失败关闭；审计型 PostTool Hook 异步、失败开放并写错误 Item。

## 结果

插件崩溃不会直接污染 App Server 堆或 Spring Context。当前实现使用 SandboxSession 常驻监督、
initialize/health handshake、请求超时/输出上限与隔离状态；任何后续优化不得重新引入主 JVM 扩展。
