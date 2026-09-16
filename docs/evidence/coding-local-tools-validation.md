# 本地文件、JShell 与系统命令验证

日期：2026-09-15。当前工作树包含此前未提交修改，整仓库门禁覆盖当前整体工作树；本文只记录本地工具增量。
未提交 Git，未调用付费模型。

本机环境：Darwin arm64，Oracle JDK `25+37-LTS-3491`，Maven 3.9.0。

## 交付边界

- Coding revision 2，共 24 个工具；新增 8 个文件工具、JShell、系统目录、系统程序和系统 Shell。
- 沿用冻结目录、权限与审批、WorkspaceExecutionPort、CodingPlatform 和原生 Sandbox。
- 新增独立文件系统 DTO/Schema、DirectoryChange、Workspace 登记及 Turn/执行快照、V010 migration。
- 原 Coding v1 DTO、Schema、PatchResult 和已有 migration 原文保持不变；旧入口的幂等意图编码保持不变。
- SDK 管理登记和查询结果；Desktop 在既有编程环境页面维护配置，并复用现有输出侧栏。

## 门禁

已通过 `mvn spotless:apply`、实际 diff 检查、`mvn spotless:check checkstyle:check`、`git diff --check`
及完整 `mvn clean verify`。最终构建于 2026-09-15 14:25:07 +08:00 完成，耗时 22 分 06 秒，
15 个 Reactor project 全部成功；日志位于 `/tmp/javaclaw-local-tools-verify-final.log`。

按本次 clean 生成的 Surefire/Failsafe XML 汇总：**3336 项测试记录，3310 项执行通过，26 项条件跳过，
0 失败、0 错误**。跳过不计为通过，计数覆盖包含此前修改的整个工作树。

| 模块 | 测试记录 | 条件跳过 |
|---|---:|---:|
| API | 106 | 0 |
| Extension SPI | 68 | 0 |
| Protocol | 157 | 0 |
| Agent Runtime | 52 | 0 |
| Model Adapters | 67 | 0 |
| Built-in Contracts | 100 | 0 |
| Built-in Extensions | 227 | 0 |
| Native Hosts | 265 | 17 |
| Browser Service | 85 | 0 |
| App Server | 1252 | 6 |
| Knowledge Worker | 9 | 0 |
| Client | 130 | 0 |
| Desktop | 720 | 0 |
| Packaging | 98 | 3 |

Desktop 包含 719 项单元测试及 1 项 Golden 集成测试。各模块行与分支覆盖率、依赖分析、
`ProductionPackageCycleTest`、`ArchitectureBoundaryTest`、Golden、SBOM、许可及发行清单校验均通过。
系统目录发现放在独立 `server.system` 基础包，持久化层没有反向依赖 Coding 执行层；没有放宽架构门禁。

本机发行 ZIP：`javaclaw-packaging/target/release/javaclaw-6.0.0-SNAPSHOT-mac-aarch64.zip`，
486476445 字节，SHA-256：`5e89ff204f60bf539c301ca53c38c943a9e3f76d6bff2c551f8e11cfc5d02311`。
产物构建成功不代表已取得其他平台的原生发行验收。

## 有意义的增量断言

| 范围 | 测试覆盖 |
|---|---|
| 文件 | 二进制往返和分页、摘要冲突、拒绝覆盖、删除权限、目录无变化/部分变化/非空删除、链接竞争、恢复原 inode 和原字节 |
| JShell | 独立 stdin 限额、资源篡改与链接拒绝、多片段、编译错误、运行异常、主动退出、独立会话、超时、取消及沙箱越界读取拒绝 |
| 系统登记 | 乐观版本、幂等更新、缺失入口、内容及同字节 inode 替换、大小写冲突、旧快照拒绝新增能力、子执行快照继承 |
| 系统执行 | 原始 argv、Shell 引号/管道/重定向、仅 Shell 逻辑授权、依赖读权限、根外读取拒绝、超时后继续执行 |
| 服务端与客户端 | V010 恢复验证、旧意图回放、运行中输出、字节游标与代码页、文件失败结果查询、DirectoryChange 历史、固定模型真实 Harness 事务 |

## JDK 21 真实执行

使用本机 Microsoft OpenJDK **21.0.7+6-LTS / aarch64**，直接执行同一受审阅 Worker 资源，9 个场景全部通过：

| 场景 | 实际退出码 |
|---|---:|
| 多片段、表达式和 UTF-8 | 0 |
| 编译错误停止 | 2 |
| 运行异常停止 | 3 |
| 不完整片段 | 2 |
| 未解析声明 | 2 |
| 主动退出 0 | 0 |
| 主动退出 7 | 7 |
| 首次会话声明变量 | 0 |
| 新会话不能读取前次变量 | 2 |

Worker SHA-256：`0fc77c588fb77ddf3db2a6ae29469c0a3f58c4ca5050040b0d38dd0e3b4b9ff4`。
这是实际 JDK 21 的 Worker 行为验证，不等同于锁定 Temurin 21.0.8 制品的安装或原生 Sandbox 验收。
JDK 25 则通过了真实 macOS Sandbox 执行、超时、取消和越界读取测试。

## 五 Runner 与限制

| Runner | 本轮状态 |
|---|---|
| macOS arm64 | 本机原生测试及完整整仓库门禁通过，详见上节 |
| macOS x64 | 未执行 |
| Linux arm64 | 未执行 |
| Linux x64 | 未执行 |
| Windows x64 | 未执行；不能用跨平台逻辑测试或条件跳过替代原生验收 |

- Windows 固定 `cmd /d /s /c` 编码、CreateProcess 长度、GetOEMCP 和系统文件 ACL 路径已实现；真实 Windows 行为仍需验证。
- 入口启动前检查真实路径、fileKey 和 SHA-256，不能承诺原子执行保证。
- Shell 授权覆盖沙箱内整个进程树；登记表不是跨平台的子命令逐条审批器。
- 直接 argv 最多 199 个非空白参数；空参数可通过 Shell 显式引号表达。混合编码不猜测转换。
- 原生结果或事务提交未知时进入 UNKNOWN_OUTCOME，不自动重放。部分结果无法在撤权/取消后确认时同样保留未知状态。
- 现有 Windows 文件替换窗口、恢复目录保留和 Gradle 网络限制仍适用，见[统一编程架构](../architecture/unified-coding-agent.md)。
