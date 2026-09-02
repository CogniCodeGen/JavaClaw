# ADR 0004：扩展双信任层

- 状态：Accepted
- 日期：2026-09-01

## 背景

随产品发布的内置能力需要事务集成；用户安装的代码则不能获得数据库、classpath 或宿主文件权限。统一动态加载
机制无法同时满足这两种信任假设。

## 决策

内置扩展随发行物编译签名并进程内注册，不使用动态 ClassLoader。第三方 Bundle 始终经签名和权限审阅后在 Sandbox
Supervisor 子进程运行，只获得显式 IPC capability 和 namespaced document/blob 存储。

## 结果

第三方能力的启动成本更高，但故障和权限边界可审计。进程外宿主未完成时保持 fail closed，不能退化为 jar 插件。
